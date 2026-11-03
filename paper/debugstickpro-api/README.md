# DebugStickPro compile-time API

Signatures of DebugStickPro's event classes and `FreezeBlockManager.isFreezeBlock`, taken from [TWME-TW/DebugStickPro](https://github.com/TWME-TW/DebugStickPro) (Apache-2.0, see LICENSE).

Only referenced by `paper:plugin` as compileOnly; at runtime the server's own DebugStickPro classes are used. The published jar must not contain `dev/twme/debugstickpro/`, which `paper:plugin`'s `verifyNoAxiom` checks.
