# AxiomPaper compile-time API

Signatures of AxiomPaper's event classes, `Integration.CustomIntegration` and the operation queue (`operations.OperationQueue`/`PendingOperation`, used to record players with the bypass permission), taken from [Moulberry/AxiomPaperPlugin](https://github.com/Moulberry/AxiomPaperPlugin) (MIT, see LICENSE).

Only referenced by `paper:plugin` as compileOnly; at runtime the server's own AxiomPaper classes are used. The published jar must not contain `com/moulberry/axiom/`, which `paper:plugin`'s `verifyNoAxiom` checks.
