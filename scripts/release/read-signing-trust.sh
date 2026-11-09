#!/usr/bin/env bash
# read-signing-trust.sh — the ONE reader of the declarations of the signing keys.
#
# Sourced, not executed. The two verifiers and the release path all ask the
# same question of the same file, and a second reader is a second authority:
# two readers of one format drift, and the drift shows up as a tag that one
# path accepts and the other refuses.
#
# This repository declares TWO sets, and both are read here: the keys that
# sign the release tags (scripts/release/signing-key.conf) and the keys that
# sign the Maven artifacts (scripts/release/maven-signing-key.conf). Two facts
# that are not the same fact do not need two readers, and one format must not
# have two parsers.
#
# WHAT IT PRINTS
#
#   <fingerprint>|<uid>|<role>
#
# one key per line, where <role> is "current" or "retired". The current key is
# the one that signs the tags cut from now on; the other ones signed the tags
# already published, and those signatures keep verifying.
#
# WHY THE SET EXISTS
#
# A rotation replaces the key that signs. If the replacement also REPLACED the
# key that the published tags were signed with, every release ever published
# would read as "signed by another key", and the audit of the published tags
# would go from its passes to none. That is not a rotation, it is an erasure
# of the history. So the file names a set, and this reader is the only thing
# that decides what that set is.
#
# THE FUNCTION
#
#   signing_trust_set <conf-file>
#
# Prints the set and returns 0. Returns 1 when the file cannot be read, when
# it declares no current key, or when it declares no current uid: a trust set
# with no current key cannot sign anything, and a caller that reads it as
# "no keys" would accept nothing at all without saying so.

signing_trust_set() {
    local conf="${1:-}"
    local current_fingerprint current_uid line fingerprint uid

    [[ -n "$conf" && -r "$conf" ]] || return 1

    current_fingerprint="$(sed -n 's/^fingerprint=//p' "$conf" | head -1 | tr -d '[:space:]')"
    current_uid="$(sed -n 's/^uid=//p' "$conf" | head -1 | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"

    [[ -n "$current_fingerprint" ]] || return 1
    [[ -n "$current_uid" ]] || return 1

    printf '%s|%s|current\n' "$current_fingerprint" "$current_uid"

    while IFS= read -r line; do
        [[ "$line" == retired=* ]] || continue
        # A line without the separator declares a key with no uid, and a key
        # with no uid cannot be checked against the uid the signature carries.
        # It is skipped here and refused by the guard that reads the same file.
        [[ "$line" == *"|"* ]] || continue
        line="${line#retired=}"
        fingerprint="${line%%|*}"
        uid="${line#*|}"
        fingerprint="$(printf '%s' "$fingerprint" | tr -d '[:space:]')"
        [[ -n "$fingerprint" && -n "$uid" ]] || continue
        printf '%s|%s|retired\n' "$fingerprint" "$uid"
    done < "$conf"

    return 0
}

# The current key of the set, and its uid, and nothing else.
#
#   signing_current_key <conf-file>
#
# Prints "<fingerprint>|<uid>". Returns 1 when the file declares no current
# key. This is the key that SIGNS: a caller that wants to sign asks here, and
# a caller that wants to VERIFY asks for the whole set.
#
# The answer comes from the set itself and not from a second reading of the
# file: two places that parse the same format inside ONE reader are the same
# drift hazard that the reader exists to remove, only closer.
signing_current_key() {
    local conf="${1:-}"
    local fingerprint uid role

    [[ -n "$conf" && -r "$conf" ]] || return 1

    while IFS='|' read -r fingerprint uid role; do
        if [[ "$role" == "current" ]]; then
            printf '%s|%s\n' "$fingerprint" "$uid"
            return 0
        fi
    done < <(signing_trust_set "$conf")

    return 1
}
