--[[
hkrime_rank.lua —— 「候选排序下移到 librime」第 2 步只读试点脚本（2026-11-10）

用法（只在真机试点，不进正式方案）：
  1. adb push tools/pilot/hkrime_rank.lua /sdcard/hkrime/lua/hkrime_rank.lua
  2. 把 hakka_char_rank.txt / hakka_char_freq.txt 也放一份到 /sdcard/hkrime/
  3. rime.lua 末尾加一行：_G.hkrime_rank = require("hkrime_rank")
  4. 目标 schema 的 engine/filters 末尾加：- lua_filter@hkrime_rank
  5. 删 /sdcard/hkrime/build/<schema>.schema.yaml 强制重建，广播 com.hazukieq.hkrime.deploy
  6. 打字，读 /sdcard/hkrime/hkrime_rank_pilot.log：
       [load] 数据表规模；[cmp] engine=引擎原序 / lua=按规则重排；
       [perf] 每次调用耗时（calls/avg/max）。
  7. 默认 yield 原顺序（只读）；把 switch 打开 hkrime_rank_apply 才真的重排。

实测结论（vivo V2520A，12 键）：
  - 数据表规模与 App 完全一致：rank=8183 freq=7990 general=9379；
  - 性能不可接受：309 条候选时 avg 25.7ms / max 94ms（纯 Lua 逐条 UTF-8 解析 + 排序，
    且跑在按键处理线程上）——App 侧同样排序约 35ms 但在后台线程且有缓存；
  - 语义上把「权威字频」应用到候选条会覆盖引擎自己的整词词频/学习排序，
    例如 455 下会把「无奇/无忌…」提到「姥姥家」之前，未必更好（需要产品决策）。
  所以结论是：排序暂时留在 App；若真要下移，必须先做 Lua 侧性能工程（按首字字节缓存、
  只重排前 N 条）或改成 C++ filter，并明确「引擎词频 vs 权威字频」的优先级。
]]
-- hkrime_rank.lua —— 候选排序「下移到 librime」的第 2 步只读试点
local M = {}

local rank, freq, general = {}, {}, {}
local loaded, load_err = false, nil
local LOG_PATH = "/sdcard/hkrime/hkrime_rank_pilot.log"

-- 老版本 librime-lua 可能没带 utf8 库，这里自带一个小实现。
local function utf8_codepoints(s)
  local out, i, n = {}, 1, #s
  while i <= n do
    local b = s:byte(i)
    local cp, adv
    if b < 0x80 then cp, adv = b, 1
    elseif b < 0xE0 then cp, adv = (b - 0xC0) * 0x40 + (s:byte(i + 1) - 0x80), 2
    elseif b < 0xF0 then
      cp = (b - 0xE0) * 0x1000 + (s:byte(i + 1) - 0x80) * 0x40 + (s:byte(i + 2) - 0x80); adv = 3
    else
      cp = (b - 0xF0) * 0x40000 + (s:byte(i + 1) - 0x80) * 0x1000 + (s:byte(i + 2) - 0x80) * 0x40 + (s:byte(i + 3) - 0x80)
      adv = 4
    end
    out[#out + 1] = cp
    i = i + adv
  end
  return out
end

local function logline(s)
  local f = io.open(LOG_PATH, "a")
  if f then f:write(s .. "\n"); f:close() end
end

local function load_table(path, tbl)
  local f = io.open(path, "r")
  if not f then return 0 end
  local n = 0
  for line in f:lines() do
    if line ~= "" and line:sub(1, 1) ~= "#" then
      local ch, v = line:match("^(.-)\t(%d+)\r?$")
      if ch and v then tbl[ch] = tonumber(v); n = n + 1 end
    end
  end
  f:close()
  return n
end

local function percentiles(tbl)
  local arr = {}
  for k, v in pairs(tbl) do arr[#arr + 1] = { k = k, v = v } end
  table.sort(arr, function(a, b) return a.v > b.v end)
  local out, n = {}, #arr
  for i = 1, n do out[arr[i].k] = math.floor((n - (i - 1)) * 10000 / n) end
  return out
end

local function ensure_loaded()
  if loaded then return end
  loaded = true
  local dir = rime_api.get_user_data_dir()
  local nr = load_table(dir .. "/hakka_char_rank.txt", rank)
  local nf = load_table(dir .. "/hakka_char_freq.txt", freq)
  local pr, pf = percentiles(rank), percentiles(freq)
  for k, v in pairs(pr) do general[k] = v end
  for k, v in pairs(pf) do
    local old = general[k]
    if old == nil or v > old then general[k] = v end
  end
  local c = 0
  for _ in pairs(general) do c = c + 1 end
  logline(string.format("[load] rank=%d freq=%d general=%d", nr, nf, c))
end

local function is_cjk_word(s)
  if s == nil or s == "" then return false end
  local cps = utf8_codepoints(s)
  if #cps < 2 then return false end
  for _, c in ipairs(cps) do
    if not ((c >= 0x3400 and c <= 0x4DBF) or (c >= 0x4E00 and c <= 0x9FFF)) then return false end
  end
  return true
end

local function gen_score(s)
  if s == nil or s == "" then return 0 end
  local cps = utf8_codepoints(s)
  if #cps == 0 then return 0 end
  local cp = cps[1]
  local b = {}
  -- 把首码点还原成字符：直接用字节切片更省事
  local first = s:match("^[%z\1-\127\194-\244][\128-\191]*")
  if first == nil then first = s:sub(1, 1) end
  return general[first] or 0
end

local function reorder(list)
  local words, others = {}, {}
  for i, cand in ipairs(list) do
    if is_cjk_word(cand.text) then words[#words + 1] = { cand = cand, i = i }
    else others[#others + 1] = { cand = cand, i = i } end
  end
  local function sort_group(g)
    table.sort(g, function(a, b)
      local sa, sb = gen_score(a.cand.text), gen_score(b.cand.text)
      if sa ~= sb then return sa > sb end
      return a.i < b.i
    end)
  end
  sort_group(words); sort_group(others)
  local out = {}
  for _, e in ipairs(words) do out[#out + 1] = e.cand end
  for _, e in ipairs(others) do out[#out + 1] = e.cand end
  return out
end

local function join(list, n, with_score)
  local parts = {}
  for i = 1, math.min(n, #list) do
    local t = list[i].text or ""
    parts[#parts + 1] = with_score and (t .. ":" .. tostring(gen_score(t))) or t
  end
  return table.concat(parts, " ")
end

local calls, total_ms, max_ms = 0, 0, 0

function M.func(input, env)
  local t0 = os.clock()
  calls = calls + 1
  pcall(ensure_loaded)

  local list, codes = {}, ""
  local ok, err = pcall(function()
    codes = env.engine.context.input or ""
    for cand in input:iter() do list[#list + 1] = cand end
  end)
  if not ok then logline("[err-iter] " .. tostring(err)) end

  logline(string.format("[cmp] input=%s n=%d engine=%s", codes, #list, join(list, 12, false)))

  local ordered = list
  local ok2, err2 = pcall(function() ordered = reorder(list) end)
  if ok2 then
    logline(string.format("[cmp] input=%s lua=%s", codes, join(ordered, 12, true)))
  else
    logline("[err-reorder] " .. tostring(err2))
  end

  local apply = false
  pcall(function() apply = env.engine.context:get_option("hkrime_rank_apply") end)
  local out = apply and ordered or list
  for _, cand in ipairs(out) do yield(cand) end
  local ms = (os.clock() - t0) * 1000
  total_ms = total_ms + ms
  if ms > max_ms then max_ms = ms end
  if calls % 10 == 0 then
    logline(string.format("[perf] calls=%d avg=%.2fms max=%.2fms n=%d", calls, total_ms / calls, max_ms, #list))
  end
end

return M
