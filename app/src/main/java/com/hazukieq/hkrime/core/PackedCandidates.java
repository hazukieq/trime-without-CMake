package com.hazukieq.hkrime.core;

import java.util.ArrayList;
import java.util.List;

/** Packed JNI records keep their engine order and all optional metadata fields. */
public final class PackedCandidates {
  private PackedCandidates() {}

  public static List<String[]> parse(String packed) {
    List<String[]> rows = new ArrayList<>();
    if (packed == null) return rows;
    int start = 0;
    while (start < packed.length()) {
      int end = packed.indexOf('\u0002', start);
      if (end < 0) break;
      String[] fields = packed.substring(start, end).split("\u0001", -1);
      if (fields.length < 2) break;
      rows.add(fields);
      start = end + 1;
    }
    return rows;
  }
}
