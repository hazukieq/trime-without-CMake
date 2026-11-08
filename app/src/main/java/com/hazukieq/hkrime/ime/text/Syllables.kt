package com.hazukieq.hkrime.ime.text

/**
 * 音節表：**直接取自 [hakka.dict.yaml] 每條詞的編碼列**（高峰腔客家話羅馬字），
 * 共 695 個音節，例如 `ngin`、`ngai`、`hau`、`mh`、`ngh`、`nh`、`cn`、`cw`、`zw`。
 * 不摻任何普通話拼音。
 *
 * 重新生成：`python3 script/gen-syllables.py`（詞典更新後跑一次）。
 */
object Syllables {

  private val TABLE: Set<String> =
    (
      "ba bai bak ban bang bat bau be ben bet bi biak biang biau bin bioi" +
      "bit biung bo boi bok bong bot bu bui buk bun bung but ca cai cak" +
      "cam can cang cap cat cau ce cem cen cet ceu ci cia ciak ciam ciang" +
      "ciau cim cin cio ciong cip cit ciu co coi cok con cong cot cu cui" +
      "cuk cun cung cut cw da dai dak dam dan dang dap dat dau de dem" +
      "den deng dep det deu di dia diak diam dian diang diap diat diau dim din" +
      "diok diong dit diu diung do doi dok don dong dot du dui duk dun dung" +
      "dut fa fai fam fan fang fat fe fen fet feu fit fo foi fon fong" +
      "fu fui fuk fun fung fut ga gai gak gam gan gang gap gat gau ge" +
      "gen gep get geu gi giak giam giang giap giau gim gin gio giok giong gip" +
      "git giu giuk giun giung go goi gok gon gong got gu gui guk gun gung" +
      "gut ha hai hak ham han hang hap hat hau he hem hen het heu hi" +
      "hiau hien him hiok hiong hit hiun hiung hng ho hoi hok hon hong hot hu" +
      "hui hun hung ja jai jak jam jan jang jap jat jau je jen jet ji" +
      "jim jin jip jit jiu jo jok jon jong jui juk jun jung jut ka kai" +
      "kak kam kan kang kap kat kau ke kem ken ket keu ki kia kiak kiam" +
      "kian kiang kiap kiau kim kin kio kioi kiok kiong kip kit kiu kiuk kiun kiung" +
      "ko koi kok kon kong ku kui kuk kun kung kut la lai lak lam lan" +
      "lang lap lat lau le len leng let leu li liak liam liang liap liau lim" +
      "lin lio liok liong lip lit liu lo lod loi lok lon long lot lu lui" +
      "luk lun lung lut ma mai mak man mang mat mau me men meng met meu" +
      "mh mi mia miang miau min mit miu mo moi mok mong mu mui muk mun" +
      "mung mut na nai nak nam nan nang nap nat nau ne nem nen neng nep" +
      "net neu nga ngai ngak ngam ngan ngang ngap ngat ngau nge ngek ngen nget ngeu" +
      "ngh ngi ngia ngiam ngik ngin ngio ngiung ngo ngoi ngok ngong ngu ngui ngun ngut" +
      "nh ni nia niam niap niau nim nin nio niok nit niu no noi nok non" +
      "nong not nu nui nuk nun nung nut nzau nzi nzia nziai nziak nziam nzian nziang" +
      "nziap nziat nziau nzien nziet nzim nzin nzio nzioi nziok nzion nziong nziot nzip nzit nziu" +
      "nziui nziuk nziun nziung nzu pa pai pak pam pan pang pat pau pe pek pen" +
      "pet peu pi piak piang piau pin piong pit po poi pok pong pu pui puk" +
      "pun pung put qa qai qak qam qan qang qap qat qau qe qem qen qet" +
      "qeu qiak qiang qiat qien qin qiong qit qiu qiun qiung qo qoi qok qon qong" +
      "qot qu quet qui quk qung qut r sa sai sak sam san sang sap sau" +
      "se sem sen set si sim sin sip sit siu siuk siung so soi sok son" +
      "song sot su sui suk sun sung sut ta tai tak tam tan tang tap tat" +
      "tau te tem ten teng tep tet teu tha thai thak tham than thang thap that" +
      "thau the them then thep thet theu thi thia thiak thiam thiang thiap thiau thim thin" +
      "thio thiok thiong thip thit thiu thiuk tho thoi thok thon thong thot thu thui thuk" +
      "thun thung thut ti tiak tiam tiap tiau tin tit to toi tok ton tong tot" +
      "tu tui tuk tun tung tut va vai vak van vang vat ve ven vet vi" +
      "vin vo voi vok von vong vu vui vuk vun vung vut za zai zak zam" +
      "zan zang zap zat zau ze zem zen zep zet zeu zi zia ziak ziam ziang" +
      "ziap ziau zim zin zio ziok ziong zip zit ziu zo zoi zok zon zong zot" +
      "zu zui zuk zun zung zut zw" +
        ""
      )
      .split(' ')
      .filter { it.isNotBlank() }
      .toSet()

  private const val MAX_LEN = 8

  data class Segment(
    val text: String,
    val startChar: Int,
    val endChar: Int,
    /** 詞典裡匹配不到（輸入進行中、或鍵盤按出的非常規串） */
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
   * 把連續輸入串切成音節：
   * - `'` 與空格視為分隔，不進入結果；
   * - 貪心最長匹配（表裡就是 hakka 詞典的音節）；
   * - 某位置不是合法音節開頭時，向後找到第一個能起音節的位置並把中間併成一組
   *   （`ja` 不會被拆成 `j`+`a`）；
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
