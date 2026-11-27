package com.hazukieq.hkrime.ime.keyboard;

import com.hazukieq.hkrime.core.Rime;
import com.hazukieq.hkrime.ime.enums.Keycode;
import java.util.HashMap;
import java.util.Map;
import timber.log.Timber;

public class RimeKeycode {
  private Map<Integer, Integer> rimeKeycode;
  private static RimeKeycode self;

  private RimeKeycode() {
    rimeKeycode = new HashMap<>();
  }

  public static RimeKeycode get() {
    if (self == null) self = new RimeKeycode();
    return self;
  }

  public int getRimeCode(int code) {
    if (!rimeKeycode.containsKey(code)) rimeKeycode.put(code, rimeCode(code));
    return rimeKeycode.get(code);
  }

  // KeyEvent keycode -> Keycode 枚举名 -> librime keycode 映射
  // https://github.com/rime/librime/blob/99e269c8eb251deddbad9b0d2c4d965b228f8006/src/rime/key_table.cc
  public static int rimeCode(int code) {
    int i = 0;
    if (code >= 0 && code < Keycode.values().length) {
      String s = Keycode.keyNameOf(code);
      i = Rime.get_keycode_by_name(s);
    } else {
      Timber.w("code=%s", code);
    }
    return i;
  }
}
