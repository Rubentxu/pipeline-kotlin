package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.isolation.AdmissionDecider
import dev.rubentxu.pipeline.v2.domain.isolation.ExecutionRequirements
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationAttestation
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationCapability
import dev.rubentxu.pipeline.v2.domain.isolation.ProvenCapability
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxConfinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The provider that MEASURES what this host can confine.
 *
 * ## What it does
 *
 * It puts a DISPOSABLE run inside the very boundary the product would apply to
 * a real run, and it reads the boundary back from inside it: which mounts it
 * got, which namespaces it has, which interfaces its network carries and what
 * happened when it tried to write inside and outside its workspace. What it
 * could read back is what it proves; what it could not is not proven, and a
 * capability it did not measure never appears in the list.
 *
 * ## Why the probe is a real run and not a lookup
 *
 * The specification forbids promising a capability by the presence of a backend,
 * and a lookup is that: `bwrap` being installed says nothing about whether the
 * kernel lets it create the namespaces, whether the root can be bound
 * read-only, or whether a write outside the workspace is refused. Those are
 * facts about a host, and the only way to know them is to ask the host.
 *
 * ## Why the probe is disposable
 *
 * It runs the product's OWN program — never the user's script, never the user's
 * Kotlin — in a directory of its own that is deleted afterwards. It leaves no
 * process behind, no file in the operator's tree and no durable state. It is
 * started BEFORE the verdict, so a refusal that follows a failed probe is a
 * refusal about the HOST and not about the request's shape.
 *
 * ## Why the probe confines itself the way a real run does
 *
 * The probe's program lives in a disposable CONTROL directory, bound read-only
 * inside the boundary, exactly as a real run's script lives in its control
 * directory. A probe that ran its program from somewhere else would be measuring
 * a plan the product does not apply: the read-only bind of the control directory
 * is part of the boundary, and it is the bind that keeps a run from publishing
 * its own verdict. A boundary that is measured without it is not the boundary
 * that runs.
 *
 * ## What it does NOT prove
 *
 * It does not prove that a real run is confined: the launcher applies the plan
 * when the step starts, and that is a fact about a run, not about a host. It
 * does not prove that the EVALUATION of a pipeline script is confined either,
 * because that happens in the product's own process, before any step exists.
 * A request from an author who is not trusted therefore stays refused, and the
 * capability it lacks is named in the refusal.
 */
internal object BwrapIsolationProvider : IsolationProvider {

    /**
     * The name the probe's program gets inside the disposable control directory.
     */
    private const val PROGRAM_NAME: String = "probe.sh"

    /** How long the probe may take before it is killed and the host is called unprobeable. */
    private const val PROBE_TIMEOUT_SECONDS: Long = 20

    /** The user namespace of THIS process, which is the host's. */
    private val HOST_USER_NAMESPACE: String = namespaceLink("user")

    /** The process namespace of THIS process, which is the host's. */
    private val HOST_PID_NAMESPACE: String = namespaceLink("pid")

    /** The network namespace of THIS process, which is the host's. */
    private val HOST_NET_NAMESPACE: String = namespaceLink("net")

    /**
     * The namespace this process is in, as the kernel names it.
     *
     * @param name the namespace's name.
     * @return the link's target, or an empty string when this host does not say.
     */
    private fun namespaceLink(name: String): String = try {
        Files.readSymbolicLink(Path.of("/proc/self/ns/$name")).toString()
    } catch (e: IOException) {
        ""
    }

    override fun attest(request: ExecutionRequirements): IsolationAttestation {
        val demanded = AdmissionDecider.requiredCapabilities(request)
        if (demanded.isEmpty()) {
            // A request that demands nothing beyond the legacy behaviour is answered by
            // the legacy posture, and NOTHING is measured to answer it: no child, no
            // probe, no read of the host.
            return IsolationAttestation.TrustedProfileOnly
        }
        return measure()
    }

    /**
     * The measurement, and what it found.
     *
     * @return the provider's own claims, each with the evidence that backs it, or the
     *   reason the host could not be measured at all.
     */
    private fun measure(): IsolationAttestation {
        val binary = SandboxConfinement.locate()
            ?: return IsolationAttestation.Unprobeable(
                "this host has no '${SandboxConfinement.TOOL}', so nothing here can apply a " +
                    "boundary to a run",
            )

        val workspace = disposableWorkspace()
            ?: return IsolationAttestation.Unprobeable(
                "this host cannot create the disposable directory the probe needs",
            )

        val controlDir = disposableControlDir()
            ?: return IsolationAttestation.Unprobeable(
                "this host cannot create the disposable directory the probe needs",
            )

        return try {
            val facts = probe(binary, workspace, controlDir)
                ?: return IsolationAttestation.Unprobeable(
                    "the boundary could not be applied on this host, so nothing can be proven " +
                        "about what it would confine",
                )
            IsolationAttestation.Provider(facts.proven())
        } finally {
            workspace.toFile().deleteRecursively()
            controlDir.toFile().deleteRecursively()
        }
    }

    /**
     * The disposable workspace the probe confines.
     *
     * @return the directory, or null when this host cannot create one.
     */
    private fun disposableWorkspace(): Path? = try {
        Files.createTempDirectory("pipelinek-probe")
    } catch (e: IOException) {
        null
    }

    /**
     * The disposable control directory the probe's own program lives in.
     *
     * The probe confines itself with the SAME plan the launcher applies to a real
     * run, and that plan binds the control directory READ-ONLY: the run reads its
     * own program there and writes nothing there. A probe that measured a plan
     * without that bind would be measuring a different boundary than the one a
     * real run gets, and would attest to nothing.
     *
     * @return the directory, or null when this host cannot create one.
     */
    private fun disposableControlDir(): Path? = try {
        Files.createTempDirectory("pipelinek-probe-control")
    } catch (e: IOException) {
        null
    }

    /**
     * Runs the probe's program inside the boundary and reads the facts back.
     *
     * @return the facts, or null when the boundary could not be applied or the program
     *   did not answer.
     */
    private fun probe(binary: Path, workspace: Path, controlDir: Path): Facts? {
        val output = try {
            Files.createTempFile("pipelinek-probe", ".out")
        } catch (e: IOException) {
            return null
        }

        val program = controlDir.resolve(PROGRAM_NAME)
        return try {
            Files.writeString(program, probeProgram(workspace, controlDir))
            program.toFile().setExecutable(true)

            val builder = ProcessBuilder(
                confinementFor(binary, workspace, controlDir).argv(listOf(program.toString())),
            )
            // The child's bytes go to a file, not to a pipe this process reads: a probe
            // that hung would otherwise hang the parse that asked for it.
            builder.redirectOutput(output.toFile())
            builder.redirectErrorStream(true)

            val process = builder.start()
            val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                null
            } else {
                Facts.parse(Files.readString(output))
            }
        } catch (e: IOException) {
            null
        } finally {
            Files.deleteIfExists(output)
        }
    }

    /**
     * The plan the probe measures.
     *
     * It is THE plan of a run, built by the same factory the launcher builds it with:
     * the probe and the launcher ask the same question, and two plans would be two
     * answers to it. A probe that measured a plan the launcher does not apply would
     * attest to a boundary that never runs.
     *
     * Internal and not private so that the rows of the test can read the plan the
     * provider really uses: the rule that the probe and the launcher share a plan is
     * one of the rules that must not be wrong, and it can only be checked against the
     * plan the product builds.
     *
     * @param binary the absolute path of the tool that applies the confinement.
     * @param workspace the disposable workspace the probe confines.
     * @param controlDir the disposable control directory the probe reads its program from.
     * @return the plan.
     */
    internal fun confinementFor(binary: Path, workspace: Path, controlDir: Path): SandboxConfinement =
        SandboxConfinement.forRun(binary, workspace, controlDir)

    /**
     * The program the probe runs inside the boundary.
     *
     * It answers with one fact per line, separated by a pipe: the facts are read
     * back from INSIDE the boundary, and each one is a value the boundary itself
     * reports.
     *
     * @param workspace the disposable workspace the probe confines.
     * @param controlDir the disposable control directory the probe reads its program from.
     * @return the program.
     */
    private fun probeProgram(workspace: Path, controlDir: Path): String = """
        #!/bin/sh
        echo "CAPS|$(grep -E '^Cap(Eff|Bnd):' /proc/self/status | tr -s ' ' | tr '\n' ' ')"
        echo "UID|$(id -u)"
        echo "NSUSER|$(readlink /proc/self/ns/user)"
        echo "NSPID|$(readlink /proc/self/ns/pid)"
        echo "NSNET|$(readlink /proc/self/ns/net)"
        echo "NETDEV|$(tail -n +3 /proc/net/dev | cut -d: -f1 | tr -d ' ' | tr '\n' ' ')"
        echo "ROOTMNT|$(grep -m1 -F ' / / ' /proc/self/mountinfo | cut -d' ' -f6)"
        echo "WSMNT|$(grep -m1 -F ' ${workspace} ' /proc/self/mountinfo | cut -d' ' -f6)"
        if touch '${workspace}/probe-inside' 2>/dev/null; then echo "WRITE_INSIDE|ok"; else echo "WRITE_INSIDE|denied"; fi
        if out=$(touch /pipelinek-probe-outside 2>&1); then echo "WRITE_OUTSIDE|ok"; else echo "WRITE_OUTSIDE|denied: ${'$'}out"; fi
        if out=$(touch '${controlDir}/probe-forged' 2>&1); then echo "WRITE_CONTROL|ok"; else echo "WRITE_CONTROL|denied"; fi
    """.trimIndent()

    /**
     * What the probe read back from inside the boundary.
     *
     * Each capability is proven by the facts that BEAR on it, and by nothing else:
     * a boundary that was applied but not observed is not a proven boundary.
     *
     * Internal and not private so that the rows of the test can read a FIXTURE of
     * facts: the rules that turn facts into claims are the ones that must not be
     * wrong, and they can only be checked against facts that are not this host's.
     */
    internal data class Facts(
        val capabilities: String,
        val uid: String,
        val userNamespace: String,
        val pidNamespace: String,
        val netNamespace: String,
        val networkDevices: String,
        val rootMount: String,
        val workspaceMount: String,
        val writeInside: String,
        val writeOutside: String,
        val writeControl: String,
    ) {

        /**
         * The capabilities these facts prove, each with the evidence that proves it.
         */
        fun proven(): List<ProvenCapability> = buildList {
            if (filesystemIsConfined()) {
                add(
                    ProvenCapability(
                        IsolationCapability.FilesystemConfinement,
                        "the root is mounted read-only ($rootMount), the workspace is mounted " +
                            "read-write ($workspaceMount), a write inside the workspace succeeded, " +
                            "a write outside it was refused ($writeOutside) and a write in the " +
                            "control directory was refused ($writeControl)",
                    ),
                )
            }
            if (networkIsConfined()) {
                add(
                    ProvenCapability(
                        IsolationCapability.NetworkConfinement,
                        "the run has a network namespace of its own ($netNamespace) and the only " +
                            "interface in it is '${networkDevices.trim()}'",
                    ),
                )
            }
            if (userNamespace.isNotEmpty() && userNamespace != BwrapIsolationProvider.HOST_USER_NAMESPACE) {
                add(
                    ProvenCapability(
                        IsolationCapability.UserNamespace,
                        "the run has a user namespace of its own ($userNamespace); the uid inside " +
                            "is $uid and it holds no capability over the host ($capabilities)",
                    ),
                )
            }
            if (pidNamespace.isNotEmpty() && pidNamespace != BwrapIsolationProvider.HOST_PID_NAMESPACE) {
                add(
                    ProvenCapability(
                        IsolationCapability.ProcessConfinement,
                        "the run has a process namespace of its own ($pidNamespace)",
                    ),
                )
            }
        }

        /**
         * Whether the filesystem was confined: the root read-only, the workspace
         * read-write, a write inside the workspace succeeded, a write outside it
         * was refused and a write in the control directory was refused.
         *
         * The control directory is one of the two places the run touches that are
         * NOT its workspace, and it is the place where the run's own verdict is
         * published. A run that could write there could publish its own success,
         * so a boundary that leaves it writable does not confine the filesystem:
         * the workspace would not be the only writable place of the operator's.
         *
         * The MOUNT is the proof and the write is its confirmation: the mount
         * options say how the boundary was applied, and the write says what a
         * process inside it experiences. The refusal's own words are carried in
         * the evidence and are NOT part of the decision: a host speaks its own
         * language, and a rule that recognised one wording would refuse a
         * boundary that is perfectly in force.
         */
        private fun filesystemIsConfined(): Boolean =
            rootMount.contains("ro") &&
                workspaceMount.contains("rw") &&
                writeInside == "ok" &&
                writeOutside.startsWith("denied") &&
                writeControl.startsWith("denied")

        /**
         * Whether the network was confined: a network namespace of the run's own, and
         * no interface in it but the loopback.
         */
        private fun networkIsConfined(): Boolean =
            netNamespace.isNotEmpty() &&
                netNamespace != BwrapIsolationProvider.HOST_NET_NAMESPACE &&
                networkDevices.trim() == "lo"

        companion object {
            /**
             * The facts the probe's program reported.
             *
             * @param output the program's output.
             * @return the facts, or null when a fact the decision depends on is missing.
             */
            fun parse(output: String): Facts? {
                val values = output.lineSequence()
                    .map { it.trim() }
                    .filter { it.contains('|') }
                    .map { line -> line.substringBefore('|').trim() to line.substringAfter('|').trim() }
                    .toMap()

                val capabilities = values["CAPS"] ?: return null
                val userNamespace = values["NSUSER"] ?: return null
                val pidNamespace = values["NSPID"] ?: return null
                val netNamespace = values["NSNET"] ?: return null
                val rootMount = values["ROOTMNT"] ?: return null
                val workspaceMount = values["WSMNT"] ?: return null
                val writeInside = values["WRITE_INSIDE"] ?: return null
                val writeOutside = values["WRITE_OUTSIDE"] ?: return null
                val writeControl = values["WRITE_CONTROL"] ?: return null

                return Facts(
                    capabilities = capabilities,
                    uid = values["UID"].orEmpty(),
                    userNamespace = userNamespace,
                    pidNamespace = pidNamespace,
                    netNamespace = netNamespace,
                    networkDevices = values["NETDEV"].orEmpty(),
                    rootMount = rootMount,
                    workspaceMount = workspaceMount,
                    writeInside = writeInside,
                    writeOutside = writeOutside,
                    writeControl = writeControl,
                )
            }
        }
    }
}
