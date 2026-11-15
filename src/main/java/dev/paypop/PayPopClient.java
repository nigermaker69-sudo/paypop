package dev.paypop;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PayPop - TikTok-live-style reactions when someone /pay's you on DonutSMP.
 * Client-side only. Cosmetic only: it reads chat and draws effects, nothing else.
 */
public class PayPopClient implements ClientModInitializer {

    // ===================== EDIT THESE =====================
    /** Amounts where the reaction level goes up: tier1, tier2, tier3 */
    static final double[] TIER_MIN = {0, 10_000, 100_000, 1_000_000}; // 0..3
    static final String[] TIER_LABEL = {"DONATION", "BIG DONATION!", "HUGE DONATION!!", "MEGA DONATION!!!"};
    static final long[] TIER_MS = {3000, 4000, 6500, 10000};
    static final float[] TIER_SCALE = {2f, 3f, 4f, 5.5f};
    static final int[] TIER_COLOR = {0x55FF55, 0xFFFF55, 0xFF9A1F, 0xFF55FF};
    // ======================================================

    /** Matches e.g. "Steve paid you $2.5m" / "$1,250" / "$30k" */
    static final Pattern PAY = Pattern.compile("(\\w{3,16}) paid you \\$([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbBtT])?");

    static final Random RNG = new Random();

    record Banner(String name, double amount, int tier, long start) {}
    static class Feed { String name; double total; int combo = 1; long last; }
    static class Confetti { float x, y, vx, vy, size; int rgb; }
    static class Task { int ticks; Runnable run; Task(int t, Runnable r) { ticks = t; run = r; } }

    static Banner banner;
    static final List<Feed> feed = new ArrayList<>();
    static final List<Confetti> confetti = new ArrayList<>();
    static final List<Task> tasks = new ArrayList<>();
    static long lastFrame = System.currentTimeMillis();

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (overlay) return;
            Matcher m = PAY.matcher(msg.getString());
            if (m.find()) {
                double amt = parse(m.group(2), m.group(3));
                Minecraft.getInstance().execute(() -> onPayment(m.group(1), amt));
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Iterator<Task> it = tasks.iterator();
            List<Runnable> due = new ArrayList<>();
            while (it.hasNext()) {
                Task t = it.next();
                if (--t.ticks <= 0) { due.add(t.run); it.remove(); }
            }
            due.forEach(Runnable::run);
        });

        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("paypop", "overlay"), PayPopClient::render);

        // /paypop test 5000000  -> fake a payment so you can preview effects
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) ->
            dispatcher.register(ClientCommandManager.literal("paypop")
                .then(ClientCommandManager.literal("test")
                    .then(ClientCommandManager.argument("amount", DoubleArgumentType.doubleArg(0))
                        .executes(c -> {
                            onPayment("TestUser", DoubleArgumentType.getDouble(c, "amount"));
                            return 1;
                        })))));
    }

    // ---------------------------------------------------------------- logic

    static double parse(String num, String suffix) {
        double v = Double.parseDouble(num.replace(",", ""));
        if (suffix == null) return v;
        return switch (Character.toLowerCase(suffix.charAt(0))) {
            case 'k' -> v * 1e3;
            case 'm' -> v * 1e6;
            case 'b' -> v * 1e9;
            case 't' -> v * 1e12;
            default -> v;
        };
    }

    static String money(double v) {
        if (v >= 1e12) return String.format("$%.2fT", v / 1e12);
        if (v >= 1e9) return String.format("$%.2fB", v / 1e9);
        if (v >= 1e6) return String.format("$%.2fM", v / 1e6);
        if (v >= 1e3) return String.format("$%.1fK", v / 1e3);
        return String.format("$%.0f", v);
    }

    static int tierOf(double amt) {
        int t = 0;
        for (int i = 0; i < TIER_MIN.length; i++) if (amt >= TIER_MIN[i]) t = i;
        return t;
    }

    static void later(int ticks, Runnable r) { tasks.add(new Task(ticks, r)); }

    static void sound(SoundEvent s, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.playSound(s, 1.0f, pitch);
    }

    static void onPayment(String name, double amount) {
        int tier = tierOf(amount);
        long now = System.currentTimeMillis();
        banner = new Banner(name, amount, tier, now);

        // TikTok-style combo feed: same sender within 5s stacks up
        Feed top = feed.isEmpty() ? null : feed.get(feed.size() - 1);
        if (top != null && top.name.equals(name) && now - top.last < 5000) {
            top.combo++; top.total += amount; top.last = now;
        } else {
            Feed f = new Feed(); f.name = name; f.total = amount; f.last = now;
            feed.add(f);
            if (feed.size() > 5) feed.remove(0);
        }

        switch (tier) {
            case 0 -> sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f);
            case 1 -> {
                sound(SoundEvents.PLAYER_LEVELUP, 1.0f);
                ring(ParticleTypes.TOTEM_OF_UNDYING, 24);
            }
            case 2 -> {
                sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
                sound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0f);
                ring(ParticleTypes.TOTEM_OF_UNDYING, 48);
                ring(ParticleTypes.FIREWORK, 32);
                burst(150);
                for (int i = 1; i <= 3; i++) later(i * 8, () -> sound(SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0f));
            }
            default -> {
                sound(SoundEvents.TOTEM_USE, 1.0f);
                sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f);
                for (int i = 0; i < 8; i++) {
                    final int n = i;
                    later(i * 6, () -> {
                        sound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.8f + n * 0.05f);
                        ring(ParticleTypes.FIREWORK, 40);
                        ring(ParticleTypes.TOTEM_OF_UNDYING, 40);
                        burst(120);
                    });
                }
                for (int i = 0; i < 60; i += 2) later(i, () -> ring(ParticleTypes.END_ROD, 6));
            }
        }
    }

    // world particles around the player (client-side only, nobody else sees them)
    static void ring(net.minecraft.core.particles.ParticleOptions p, int count) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        for (int i = 0; i < count; i++) {
            double a = RNG.nextDouble() * Math.PI * 2;
            double r = 0.8 + RNG.nextDouble() * 1.5;
            mc.level.addParticle(p, px + Math.cos(a) * r, py + 0.2 + RNG.nextDouble() * 2.2, pz + Math.sin(a) * r,
                    0, 0.15 + RNG.nextDouble() * 0.25, 0);
        }
    }

    static void burst(int n) {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        for (int i = 0; i < n; i++) {
            Confetti c = new Confetti();
            c.x = RNG.nextFloat() * w;
            c.y = -10 - RNG.nextFloat() * 60;
            c.vx = (RNG.nextFloat() - 0.5f) * 80;
            c.vy = 60 + RNG.nextFloat() * 140;
            c.size = 2 + RNG.nextInt(3);
            c.rgb = Mth.hsvToRgb(RNG.nextFloat(), 0.9f, 1f) & 0xFFFFFF;
            confetti.add(c);
        }
    }

    // ---------------------------------------------------------------- HUD

    static int argb(int alpha, int rgb) { return (Mth.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF); }

    static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        float dtSec = Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        int w = g.guiWidth(), h = g.guiHeight();

        // confetti
        Iterator<Confetti> ci = confetti.iterator();
        while (ci.hasNext()) {
            Confetti c = ci.next();
            c.x += c.vx * dtSec; c.y += c.vy * dtSec;
            c.vx += (RNG.nextFloat() - 0.5f) * 40 * dtSec;
            if (c.y > h + 10) { ci.remove(); continue; }
            g.fill((int) c.x, (int) c.y, (int) c.x + (int) c.size, (int) c.y + (int) c.size, argb(255, c.rgb));
        }

        // banner
        if (banner != null) {
            long el = now - banner.start();
            long dur = TIER_MS[banner.tier()];
            if (el > dur) {
                banner = null;
            } else {
                int tier = banner.tier();

                // mega gold flash
                if (tier == 3 && el < 700) {
                    g.fill(0, 0, w, h, argb((int) (110 * (1 - el / 700f)), 0xFFD700));
                }

                // pop-in with overshoot, fade-out at the end
                float pop = Math.min(1f, el / 350f);
                float c1 = 1.70158f, c3 = c1 + 1;
                float ease = 1 + c3 * (float) Math.pow(pop - 1, 3) + c1 * (float) Math.pow(pop - 1, 2);
                float fade = el > dur - 600 ? (dur - el) / 600f : 1f;
                int alpha = (int) (255 * fade);
                int color = tier == 3 ? Mth.hsvToRgb((el % 1500) / 1500f, 0.8f, 1f) : TIER_COLOR[tier];
                float s = TIER_SCALE[tier] * ease;
                float wobble = tier >= 2 ? (float) Math.sin(el / 90.0) * 2f : 0f;

                float cy = h * 0.22f;
                // label
                g.pose().pushMatrix();
                g.pose().translate(w / 2f, cy - 14 * s / 2f);
                g.pose().scale(s * 0.55f, s * 0.55f);
                g.drawCenteredString(mc.font, Component.literal(TIER_LABEL[tier]), 0, 0, argb(alpha, color));
                g.pose().popMatrix();

                // amount (big)
                g.pose().pushMatrix();
                g.pose().translate(w / 2f + wobble, cy + 6 * s / 2f);
                g.pose().scale(s, s);
                g.drawCenteredString(mc.font, Component.literal(money(banner.amount())), 0, 0, argb(alpha, 0xFFFFFF));
                g.pose().popMatrix();

                // sender
                g.pose().pushMatrix();
                g.pose().translate(w / 2f, cy + 22 * s / 2f);
                g.pose().scale(s * 0.6f, s * 0.6f);
                g.drawCenteredString(mc.font, Component.literal("from " + banner.name()), 0, 0, argb(alpha, color));
                g.pose().popMatrix();
            }
        }

        // TikTok-style gift feed (left side)
        feed.removeIf(f -> now - f.last > 8000);
        int y = h / 2;
        for (Feed f : feed) {
            float a = Math.min(1f, (8000 - (now - f.last)) / 1000f);
            int al = (int) (255 * a);
            String line = f.name + " sent " + money(f.total) + (f.combo > 1 ? "  x" + f.combo : "");
            int tw = mc.font.width(line);
            g.fill(6, y - 3, 6 + tw + 10, y + 12, argb((int) (150 * a), 0x000000));
            g.fill(6, y - 3, 9, y + 12, argb(al, TIER_COLOR[tierOf(f.total)]));
            g.drawString(mc.font, line, 13, y + 1, argb(al, 0xFFFFFF), true);
            y += 18;
        }
    }
}
