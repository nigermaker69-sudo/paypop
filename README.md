# PayPop (Fabric, MC 1.21.11, client-side)

Reacts to DonutSMP "<name> paid you $X" chat messages with TikTok-live-style effects.

## Build
1. Go to https://fabricmc.net/develop, pick 1.21.11, generate the template (gives you gradle wrapper + correct versions).
2. Copy `src/main/java/dev/paypop/PayPopClient.java` and `src/main/resources/fabric.mod.json` into it
   (or use this folder and fix `fabric_version` in gradle.properties, then add the wrapper).
3. `./gradlew build` -> jar in `build/libs/`. Drop in `.minecraft/mods` with Fabric API.

## Test without anyone paying you
`/paypop test 50000`   `/paypop test 5000000`

## Tiers (edit at top of PayPopClient.java)
- under $10K: small ding
- $10K+: level-up sound, totem ring, banner
- $100K+: confetti, fireworks, big shaking banner
- $1M+: gold flash, rainbow text, 8 firework volleys, confetti storm
