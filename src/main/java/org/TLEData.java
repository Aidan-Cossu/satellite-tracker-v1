package org;

/**
 * The two lines of a TLE (two-line element set), the standard text format for describing an
 * orbit. Line 1 starts with "1 " and line 2 with "2 ".
 */
public record TLEData(String line1, String line2) {}

