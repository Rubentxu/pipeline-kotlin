// LFC-2E2 utilities OFFICIAL_PLUGIN live fixture (Slice 2 / S2.4 + S2.5).
//
// End-to-end defence-in-depth proof of CVE-2023-32981 containment on
// BOTH sides of the zip chain. The fixture:
//
//   1) Creates a benign zip archive (via the typed zipFiles Step).
//   2) Replaces the local-file-header entry-name bytes with a
//      parent-segment name "../escaped.txt" using a `core.sh` one-liner.
//      The body / CRC remain valid (we just renamed the entry), so the
//      resulting file is parseable as a real zip; only the entry name
//      has been smuggled.
//   3) Runs `core-utils.unzip` against the malicious archive.
//   4) The handler MUST reject the archive with a typed USER failure
//      containing "Zip Slip". The pipeline as a whole fails with a
//      typed failure.
//
// Note: this fixture intentionally triggers a typed failure. The
// CompatibilityCorpusTest recognises it as a known-good negative case.
import dev.rubentxu.pipeline.v2.sdk.utilities.step.zipFiles
import dev.rubentxu.pipeline.v2.sdk.utilities.step.unzip

pipeline {
    stages {
        stage("zip-slip-defense") {
            // 1) Create a benign zip via the typed Step. The source
            //    file has a deliberately-14-char workspace-relative
            //    name so the LFH / CDH entry-name field can be
            //    swapped in-place (same length -> no CDH/EOCD
            //    re-alignment needed). The file lives at the
            //    workspace root (no directory prefix) so the
            //    workspace-relative path IS exactly the 14-char
            //    basename.
            sh("echo 'benign' > abcdefghijklmn")
            zipFiles(
                path = "build/utils/malicious.zip",
                paths = listOf("abcdefghijklmn"),
                overwrite = true,
            )

            // 2) Splice the entry name in BOTH the local file header
            //    and the central-directory header to "../escaped.txt"
            //    (14 chars). The file body / CRC remain valid; only
            //    the entry name has been smuggled.
            //
            //    We pad the original entry name "f" to exactly 14
            //    bytes by replacing it in-place with the smuggled
            //    name (no length change -> the rest of the CDH stays
            //    aligned -> ZipFile parses the archive cleanly and
            //    hands the smuggled name to our handler).
            sh("""
                python3 - <<'PY'
import struct
p = 'build/utils/malicious.zip'
data = bytearray(open(p, 'rb').read())
assert data[0:4] == b'PK\x03\x04', 'expected LFH at offset 0, got ' + repr(bytes(data[0:4]))
fixed_lfh = 30
orig_name_len = struct.unpack('<H', data[26:28])[0]
new_name = b'../escaped.txt'
new_name_len = len(new_name)
assert orig_name_len == new_name_len, (
    f'LFH name length {orig_name_len} != {new_name_len}; rerun after a clean'
)
# Patch LFH name in-place.
data[fixed_lfh:fixed_lfh + new_name_len] = new_name
# Patch EOCD offset (no delta in length -> unchanged, but read it for
# the assertion so the splice stays auditable).
eocd = data.rfind(b'PK\x05\x06')
cd_offset = struct.unpack('<I', data[eocd + 16:eocd + 20])[0]
# Patch CDH name in-place (same length, same alignment).
assert data[cd_offset:cd_offset + 4] == b'PK\x01\x02', 'CDH signature missing'
cdh_name_len = struct.unpack('<H', data[cd_offset + 28:cd_offset + 30])[0]
assert cdh_name_len == new_name_len, f'CDH name length {cdh_name_len} != {new_name_len}'
data[cd_offset + 46:cd_offset + 46 + new_name_len] = new_name
open(p, 'wb').write(bytes(data))
print('Spliced entry name:', repr(new_name), '(in both LFH and CDH)')
PY
            """.trimIndent())

            // 3) Attempt the extract. The handler MUST raise a typed
            //    USER failure with the message "Zip Slip". The pipeline
            //    therefore fails as a whole.
            unzip(path = "build/utils/malicious.zip")
        }
    }
}
