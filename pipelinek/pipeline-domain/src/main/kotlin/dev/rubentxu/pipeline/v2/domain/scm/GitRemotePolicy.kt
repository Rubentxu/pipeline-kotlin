package dev.rubentxu.pipeline.v2.domain.scm

/**
 * The remote a checkout may point at, and the refusal of the ones that may not.
 *
 * **Why this rule exists.** git reads its own command line the way every POSIX
 * program does: an argument that starts with `-` is an OPTION, not a value. So
 * a remote that begins with `--upload-pack=…` is not a repository — it is an
 * instruction to git to run a program of the caller's choosing. With a
 * `file://` remote that program runs on THIS machine, and it runs before git
 * has decided whether the repository exists at all. The same is true of git's
 * `ext::` transport helper, which runs a shell command by design.
 *
 * The rule is therefore stated twice over, because either half alone leaves a
 * hole: the remote must not LOOK like an option, and the argv must not let an
 * option be read out of a positional. `GitCheckoutExecutor` and
 * `GitPollExecutor` put `--` before their positionals; this object decides
 * what may be a positional at all.
 *
 * **Why the transports are a closed list.** The product's documented remotes
 * are `https://`, `ssh://`, `file://` and the scp-like SSH form
 * (`git@host:path`), which is the same SSH transport spelled without a scheme.
 * Everything else is refused, and the refusal names the list: `ext::` (a shell
 * command by design), `ftp://`, a bare local path (ambiguous with a relative
 * path and never a remote the product claimed to support) and plain `http://`
 * (no server authentication at all, so a token in it travels in clear text).
 *
 * **Why the refusal does not repeat the remote.** The remote is author-supplied
 * and may carry a credential, so the message names the FIELD and the RULE and,
 * at most, the option NAME (`--upload-pack`), never the value. A refusal that
 * echoed the offending string would publish into the run's durable record the
 * very secret the refusal exists to keep out of it.
 */
object GitRemotePolicy {

    /**
     * The transports a checkout may use. `https` and `ssh` are authenticated
     * remotes; `file` is the local one an air-gapped install uses.
     */
    val ALLOWED_SCHEMES: Set<String> = setOf("https", "ssh", "file")

    /**
     * The scp-like SSH form (`git@host:path`): the SSH transport without a
     * scheme. It is a remote, not a path, and refusing it would refuse the
     * ordinary way of cloning over SSH.
     */
    private val SCP_LIKE_SSH = Regex("^[^/@\\s:]+@[^/\\s:]+:.+$")

    /**
     * A git option name, as it would appear in argv: a dash, then letters.
     * Only a string that LOOKS like an option is named in the refusal; anything
     * else is described without being repeated.
     */
    private val OPTION_NAME = Regex("^--?[A-Za-z][A-Za-z0-9-]*")

    /**
     * A remote refused by this policy. It is a TYPED refusal so that the
     * caller can classify it by type: the author's remote is wrong, which is
     * not the same fact as the infrastructure being broken.
     */
    class GitRemoteRefused(message: String) : IllegalArgumentException(message)

    /**
     * The refusal this remote deserves, or `null` when it may be used.
     *
     * The order matters: an option-shaped remote is refused as an option even
     * though it also has no allowed scheme, because that is the more useful
     * thing to tell the author — and the more urgent one, since it is the
     * shape that runs a command.
     */
    fun refusalFor(url: String, branch: String): String? {
        if (url.startsWith("-")) {
            return "the remote url looks like a git option (${
                optionNameOf(url) ?: "an option"
            }), so git would run it as an instruction instead of fetching a repository"
        }
        if (branch.startsWith("-")) {
            return "the branch looks like a git option, so git would read it as an " +
                "instruction instead of a branch name"
        }
        if (!isAllowed(url)) {
            return "the remote url does not use one of the allowed transports " +
                "(${ALLOWED_SCHEMES.sorted().joinToString(", ")}), and the scp-like " +
                "SSH form (git@host:path)"
        }
        return null
    }

    /**
     * Refuses a remote that may not be used. Throws [GitRemoteRefused] with
     * the reason.
     */
    fun requireSafeRemote(url: String, branch: String) {
        refusalFor(url, branch)?.let { throw GitRemoteRefused(it) }
    }

    /**
     * Whether this remote may be used: an allowed scheme, or the scp-like SSH
     * form.
     */
    fun isAllowed(url: String): Boolean {
        val scheme = schemeOf(url) ?: return SCP_LIKE_SSH.matches(url)
        return scheme in ALLOWED_SCHEMES
    }

    /**
     * The scheme of a remote, lowercased, or `null` when it has none.
     *
     * The scheme is the text before the first `:` — but only when what follows
     * looks like a path or a host. A Windows drive letter (`C:\repo`) and a
     * scp-like remote (`git@host:path`) have a colon without a scheme.
     */
    private fun schemeOf(url: String): String? {
        val colon = url.indexOf(':')
        if (colon <= 0) return null
        val candidate = url.substring(0, colon)
        if (!candidate.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        if (candidate.length == 1 && candidate[0].isLetter()) return null
        return candidate.lowercase()
    }

    /**
     * The option NAME of an option-shaped remote, or `null` when the string
     * does not look like one (in which case the refusal describes it without
     * repeating it).
     */
    private fun optionNameOf(url: String): String? {
        val name = url.substringBefore('=')
        if (!OPTION_NAME.matches(name)) return null
        return name
    }
}
