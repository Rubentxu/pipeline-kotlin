package dev.rubentxu.pipeline.v2.domain.step

/**
 * Minimal SemVer value object for plugin release identity (PLUGIN_IDENTITY_MODEL).
 *
 * Deliberately frozen shape: only `major`, `minor`, `patch`. Pre-release and
 * build metadata are NOT supported in this cycle; introducing them is an ADR
 * that brings its own version-comparison semantics. The canonical textual
 * representation is `MAJOR.MINOR.PATCH`.
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int) {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "SemVer components must be non-negative: $major.$minor.$patch"
        }
    }

    override fun toString(): String = "$major.$minor.$patch"
}

/**
 * Opaque content digest used for plugin-release identity (PLUGIN_IDENTITY_MODEL).
 *
 * Accepts the textual form `sha256:<64-hex>`. Validation is fail-closed: any
 * deviation (wrong algorithm prefix, wrong length, non-hex chars) throws.
 * The internal storage form is the lowercase prefix plus the hex payload.
 */
data class Digest(val value: String) {
    init {
        require(value.startsWith("sha256:")) {
            "Digest must start with 'sha256:' (got: '$value')"
        }
        val hex = value.removePrefix("sha256:")
        require(hex.length == 64) {
            "Digest hex payload must be 64 chars (got ${hex.length})"
        }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Digest hex payload must be hexadecimal (got: '$hex')"
        }
    }
}
