package com.hazukieq.hkrime.ime.text

/**
 * 拼音音節切分（純顯示用途）。
 *
 * librime 只在「已轉換段落」上暴露 `sel_start/sel_end`，連續輸入階段不提供音節切分
 * （見 `librime/src/rime/composition.cc` 的 `GetPreedit()`）。展開面板需要「左側拼音組」，
 * 這裡用一張無聲調音節表做貪心最長匹配，自己切。
 *
 * 注意：這是**顯示/導航**用的切分，點擊某一組只能把 caret 移到該組起點，
 * 不保證右側候選會切換成該組的候選（librime 沒有「按段取候選」的 API）。
 */
object PinyinSyllables {

  /** 無聲調音節表（含少量自成音節的 m/n/ng）。 */
  private val TABLE: Set<String> =
    (
      "a ai an ang ao " +
        "ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu " +
        "ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo " +
        "da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo " +
        "e ei en eng er " +
        "fa fan fang fei fen feng fiao fo fou fu " +
        "ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo " +
        "ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo " +
        "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun " +
        "ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo " +
        "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lue lun luo lv lve " +
        "ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu m " +
        "na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nue nuo nv nve n ng " +
        "o ou " +
        "pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu " +
        "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun " +
        "ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo " +
        "sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo " +
        "ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo " +
        "wa wai wan wang wei wen weng wo wu " +
        "xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun " +
        "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun " +
        "za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo"
      )
      .split(' ')
      .filter { it.isNotBlank() }
      .toSet()

  private const val MAX_LEN = 6

  data class Segment(
    val text: String,
    val startChar: Int,
    val endChar: Int,
    /** 表裡匹配不到（如尾部還沒打完的 `sen` 前綴、或者鍵盤上按出的非法串） */
    val incomplete: Boolean,
  )

  private fun longestAt(raw: String, i: Int): Int {
    var len = minOf(MAX_LEN, raw.length - i)
    while (len >= 1) {
      if (TABLE.contains(raw.substring(i, i + len).lowercase())) return len
      len--
    }
    return 0
  }

  private fun isSep(c: Char) = c == '\'' || c == ' '

  /**
   * 把連續輸入串切成音節。
   *
   * - `'` 與空格視為音節分隔，不進入結果文本；
   * - 貪心最長匹配；某位置不是合法音節開頭時，向後找到第一個能起音節的位置，
   *   把中間那段併進去（`ja` 不會被拆成 `j`+`a`）；
   * - 匹配不到完整音節的剩餘串作為一組並標記 incomplete。
   */
  fun split(raw: String): List<Segment> {
    if (raw.isEmpty()) return emptyList()
    val out = ArrayList<Segment>()
    var i = 0
    while (i < raw.length) {
      if (isSep(raw[i])) {
        i++
        continue
      }
      val m = longestAt(raw, i)
      if (m > 0) {
        out.add(Segment(raw.substring(i, i + m), i, i + m, false))
        i += m
        continue
      }
      var j = i + 1
      while (j < raw.length && !isSep(raw[j]) && longestAt(raw, j) == 0) j++
      if (j < raw.length && !isSep(raw[j])) {
        val m2 = longestAt(raw, j)
        out.add(Segment(raw.substring(i, j + m2), i, j + m2, true))
        i = j + m2
      } else {
        out.add(Segment(raw.substring(i, j), i, j, true))
        i = j
      }
    }
    return out
  }
}
