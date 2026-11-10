# DonutPayback (Fabric 1.21.11)

Client-side payment ledger for DonutSMP.

## Features
- Watches outgoing `/pay <player> <amount>` commands.
- Understands plain numbers and `k`, `m`, `b` suffixes (for example `10m`).
- Saves outgoing payments as pending until you confirm the server accepted them.
- Tracks confirmed amounts owed, with a local ledger in `.minecraft/config/donutpayback-ledger.txt`.
- Provides `/payback list`, `/payback confirm <player>`, `/payback request <player>`, and `/payback forgive <player>`.

## Important limitations
This is a client-side tracker, not a server economy integration. It cannot verify DonutSMP's exact payment-success message, force another player to repay, or withdraw money from another player. After you see the server confirm your `/pay`, run `/payback confirm <player>` to move that payment from pending to the owed ledger. `/payback request <player>` sends a normal `/msg` reminder. Use it only where server rules permit.

## Build
Run `./gradlew build` (Windows: `gradlew.bat build`). The remapped mod JAR is in `build/libs/`. Requires Minecraft 1.21.11, Fabric Loader, Fabric API, and Java 21 at runtime.
