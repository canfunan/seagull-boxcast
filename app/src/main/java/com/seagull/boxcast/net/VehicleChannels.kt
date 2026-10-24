package com.seagull.boxcast.net

/**
 * 车型渠道表（com.baidu.carlife v8.9.5 CommonParams.VehicleChannel enum 全量实证，63 项）。
 *
 * 格式：2 开头的 8 位数字（`2\d{7}`），来源 = CarLife 握手时车机自报
 * （VideoResolution.getVechileChannel：isCarlifeConnected && hasRecvChannel
 *   → CommonParams.vehicleChannel.getVehicleChannel()；断连用 SharedPreferences 缓存）。
 *
 * 编码规律：前 4 位 = 品牌/厂商段，后 4 位 = x100 品牌默认 / x1xx 具体车型 / x2xx 双音频变体。
 *
 * 用途（官方优化体系）：AMIS 云端按 cn 匹配下发专属码率+帧率
 * （[{"cn":"20181100","rate":码率,"frame":帧率,"highrate":bool}]）；
 * 本表是自建车型参数库的基础——把你的实测最优参数按 cn 索引即可复刻官方机制。
 */
object VehicleChannels {

    // 市场/通用
    const val AFTERMARKET = "20000000"        // 后装市场
    const val PREINSTALLMARKET = "20000001"   // 前装预装
    const val NORMAL = "20011100"             // 普通
    const val DUAL_AUDIO_TRACK = "20012100"   // 双音频轨

    // 合资品牌（前装）
    const val HYUNDAI = "20022100"
    const val HYUNDAI_LFSONATA = "20022101"
    const val HYUNDAI_CFMISTRA = "20022102"
    const val HYUNDAI_MDAVANTE = "20022103"
    const val HYUNDAI_LMIX35 = "20022104"
    const val HYUNDAI_GCIX25 = "20022105"
    const val HYUNDAI_GRANDSANTEFE = "20022106"
    const val KIA = "20032100"
    const val KIA_KX5 = "20032101"
    const val KIA_K3 = "20032102"
    const val AUDI = "20041100"
    const val AUDI_DUAL_AUDIO = "20042100"
    const val DAIMLER = "20062100"
    const val SHANGHAIGM = "20051100"
    const val PANASONIC = "20071100"
    const val VOLKSWAGEN = "20081100"
    const val HARMAN = "20101100"
    const val DELPHI = "20111100"
    const val ALPINE = "20131100"
    const val PIONEER = "20141100"
    const val WISTEON = "20171100"

    // 上汽通用系
    const val SHANGHAIGM_CADILLAC = "20181100"
    const val SHANGHAIGM_CADILLAC_DUAL_AUDIO = "20182100"
    const val SHANGHAIGM_BUICK = "20191100"
    const val SHANGHAIGM_BUICK_DUAL_AUDIO = "20192100"
    const val SHANGHAIGM_CHEVROLET = "20201100"
    const val SHANGHAIGM_CHEVROLET_DUAL_AUDIO = "20202100"

    // 自主品牌
    const val BYD = "20211100"
    const val CHANGAN = "20092100"
    const val CHANGAN_CS15 = "20092101"
    const val CHANGAN_V716 = "20092102"
    const val CHANGAN_CS3516 = "20092103"
    const val CHANGAN_YIDONG16 = "20092104"
    const val CHANGAN_RUICHENG16 = "20092105"
    const val GELLYAUTO_GEOMETRY = "20312173"

    // 丰田系
    const val YIQIFENGTIAN_COROLLA_DA = "20282119"
    const val YIQIFENGTIAN_COROLLA_GSHV = "20282120"
    const val IZOA_PANASONIC = "20282126"
    const val TOYOTA_RAV4 = "21070001"
    const val GUANGFENG_LEVIN = "20452100"        // 广汽丰田 雷凌
    const val GUANGFENG_HIGHLANDER = "20452101"   // 汉兰达
    const val GUANGFENG_AVN = "20452102"
    const val GUANGFENG_LEVIN_PHV = "20452106"

    // 后装厂/方案商
    const val HUAYANG_MTK3360 = "20122100"
    const val HUAYANG_MTK8317 = "20122101"
    const val ROADROVER_MSTAR786 = "20152100"
    const val ROADROVER_MSTAR786_2 = "20152200"
    const val ROADROVER_QUANZHI = "20152101"
    const val ROADROVER_MTK8317 = "20152102"
    const val FEIGE_G6 = "20162100"               // 飞歌
    const val FEIGE_G8 = "20162101"
    const val FEIGE_G9 = "20162102"
    const val YUANFENG = "20222101"               // 远峰
    const val YUANFENG_CSRA = "20222102"
    const val CHEVROLET_K216 = "20222103"
    const val KAIYUE = "20302100"
    const val YUANFENG_ELH_PCBA = "20482100"
    const val YUANFENG_ELH_ONLINE = "20482101"
    const val YUANFENG_YILI = "20542100"

    const val OTHER = ""

    /** 渠道 → 中文名（选车 UI/日志用；car_brand.json 有完整品牌库 1.28MB） */
    val NAMES = mapOf(
        AFTERMARKET to "后装市场", PREINSTALLMARKET to "前装预装", NORMAL to "普通",
        HYUNDAI to "现代", HYUNDAI_LFSONATA to "现代 朗动", HYUNDAI_CFMISTRA to "现代 名图",
        HYUNDAI_MDAVANTE to "现代  Avante", HYUNDAI_LMIX35 to "现代 IX35",
        HYUNDAI_GCIX25 to "现代 IX25", HYUNDAI_GRANDSANTEFE to "现代 全新胜达",
        KIA to "起亚", KIA_KX5 to "起亚 KX5", KIA_K3 to "起亚 K3",
        AUDI to "奥迪", AUDI_DUAL_AUDIO to "奥迪(双音频)", DAIMLER to "戴姆勒",
        SHANGHAIGM to "上汽通用", PANASONIC to "松下", VOLKSWAGEN to "大众",
        HARMAN to "哈曼", DELPHI to "德尔菲", ALPINE to "阿尔派", PIONEER to "先锋",
        WISTEON to "伟世通",
        SHANGHAIGM_CADILLAC to "凯迪拉克", SHANGHAIGM_CADILLAC_DUAL_AUDIO to "凯迪拉克(双音频)",
        SHANGHAIGM_BUICK to "别克", SHANGHAIGM_BUICK_DUAL_AUDIO to "别克(双音频)",
        SHANGHAIGM_CHEVROLET to "雪佛兰", SHANGHAIGM_CHEVROLET_DUAL_AUDIO to "雪佛兰(双音频)",
        BYD to "比亚迪",
        CHANGAN to "长安", CHANGAN_CS15 to "长安 CS15", CHANGAN_V716 to "长安 V716",
        CHANGAN_CS3516 to "长安 CS3516", CHANGAN_YIDONG16 to "长安 逸动", CHANGAN_RUICHENG16 to "长安 睿成",
        GELLYAUTO_GEOMETRY to "吉利几何",
        YIQIFENGTIAN_COROLLA_DA to "一汽丰田 卡罗拉", YIQIFENGTIAN_COROLLA_GSHV to "一汽丰田 卡罗拉双擎",
        IZOA_PANASONIC to "丰田 IZOA", TOYOTA_RAV4 to "丰田 RAV4",
        GUANGFENG_LEVIN to "广汽丰田 雷凌", GUANGFENG_HIGHLANDER to "广汽丰田 汉兰达",
        GUANGFENG_AVN to "广汽丰田 AVN", GUANGFENG_LEVIN_PHV to "广汽丰田 雷凌PHV",
        HUAYANG_MTK3360 to "华阳 MTK3360", HUAYANG_MTK8317 to "华阳 MTK8317",
        ROADROVER_MSTAR786 to "路畅 Mstar786", ROADROVER_MSTAR786_2 to "路畅 Mstar786(2)",
        ROADROVER_QUANZHI to "路畅 全志", ROADROVER_MTK8317 to "路畅 MTK8317",
        FEIGE_G6 to "飞歌 G6", FEIGE_G8 to "飞歌 G8", FEIGE_G9 to "飞歌 G9",
        YUANFENG to "远峰", YUANFENG_CSRA to "远峰 CSRA", CHEVROLET_K216 to "雪佛兰 K216",
        KAIYUE to "开悦", YUANFENG_ELH_PCBA to "远峰 ELH PCBA", YUANFENG_ELH_ONLINE to "远峰 ELH 在线",
        YUANFENG_YILI to "远峰 伊利",
        DUAL_AUDIO_TRACK to "双音频轨", OTHER to "其他",
    )

    /** 是否合法渠道号（VehicleChannelUtils.checkChannel 实证：2\d{7}） */
    fun isValid(channel: String): Boolean =
        channel.length == 8 && channel.startsWith("2") && channel.all { it.isDigit() }
}
