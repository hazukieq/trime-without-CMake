-- hkrime: rime-ice 一类方案需要的 Lua 模块入口
--
-- librime-lua 的 `lua_translator@*name` / `lua_filter@*name` / `lua_processor@*name`
-- 会到全局环境里找名为 name 的变量。官方 rime.lua 做的就是把这些模块 require
-- 出来挂到全局，这里按本机 lua/ 目录里实际存在的脚本重建一份（缺哪个就跳过，
-- 不影响其它模块，也不影响没有 Lua 的方案）。

local modules = {
  -- translators
  "date_translator",
  "lunar",
  "uuid",
  "unicode",
  "number_translator",
  "calc_translator",
  -- processors / filters
  "select_character",
  "corrector",
  "autocap_filter",
  "v_filter",
  "pin_cand_filter",
  "long_word_filter",
  "reduce_english_filter",
  "search",
  "force_gc",
}

for _, name in ipairs(modules) do
  local ok, mod = pcall(require, name)
  if ok and mod ~= nil then
    _G[name] = mod
  end
end
