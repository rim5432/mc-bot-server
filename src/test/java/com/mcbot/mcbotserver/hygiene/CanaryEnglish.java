package com.mcbot.mcbotserver.hygiene;

/**
 * English-only gate canary: the comment below carries a CJK codepoint so
 * EnglishOnlyScan has a known hit. Without this canary, a gate that
 * silently stopped scanning (walk root moved, file filter changed) would
 * still pass with zero violations — the empty-set assertion cannot
 * distinguish "scanned and found nothing" from "never scanned".
 *
 * <p>canary(english): 汉
 */
public final class CanaryEnglish {
    private CanaryEnglish() {}
}
