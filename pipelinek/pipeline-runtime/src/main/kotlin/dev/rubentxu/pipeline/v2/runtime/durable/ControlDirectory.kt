package dev.rubentxu.pipeline.v2.runtime.durable

import java.nio.file.Path

/**
 * The control directory of ONE operation: the substrate's own home for it.
 *
 * ## What this owns
 *
 * The place where the substrate keeps what an operation left behind: the captured stdout, the
 * published exit code, the timeout marker and the console transcript. The rule that says what
 * may be released from there is the substrate's, and this function only says WHERE that home
 * is.
 *
 * ## Why the derivation lives here and not in each caller
 *
 * The home of an operation is named by the operation's [OpId] in its formatted form, and the
 * formatted form is the identity the substrate files things under. Two callers need that home:
 * the shell, which writes into it, and the confirmation, which releases it. A caller that
 * derived the path on its own would be a second authority for where the substrate keeps its
 * evidence, and the two would be free to disagree.
 *
 * ## Why this is NOT the journal's operation id
 *
 * The journal names an operation by its OWN identity, and on the scripted surface that identity
 * is not the [OpId]'s formatted form: it is the scripted call site's stable key. The two name
 * the same operation and neither is derived from the other, so the substrate's home has to be
 * named by the substrate's identity — the [OpId] — and not by whatever the journal happens to
 * call the operation.
 */
internal fun controlDirOf(controlDirRoot: Path, opId: OpId): Path = controlDirRoot.resolve(opId.format())
