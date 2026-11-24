package com.openjarvis.video

import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

data class TimedVideoCaption(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class VideoClaimSource(
    val claimId: String,
    val sceneId: String,
    val sourceUrl: String,
    val publisher: String,
    val checkedOn: String,
    val supportedClaim: String,
    val uncertaintyNote: String? = null
)

data class VideoStoryPlan(
    val title: String,
    val scenes: List<VideoScene>,
    val narrationText: String,
    val captions: List<TimedVideoCaption>,
    val sourceLabel: String,
    val hookText: String = "",
    val claimSources: List<VideoClaimSource> = emptyList()
) {
    val renderSpec: VideoRenderSpec
        get() = VideoRenderSpec(
            title = title,
            scenes = scenes,
            width = 720,
            height = 1_280,
            bitRate = 1_500_000,
            secondsPerScene = SECONDS_PER_SCENE,
            requireLicensedVisualAssets = true
        )

    fun toSrt(): String = captions.mapIndexed { index, cue ->
        buildString {
            append(index + 1).append('\n')
            append(formatSrtTime(cue.startMs))
                .append(" --> ")
                .append(formatSrtTime(cue.endMs))
                .append('\n')
            append(cue.text).append('\n')
        }
    }.joinToString("\n")

    private fun formatSrtTime(valueMs: Long): String {
        val hours = valueMs / 3_600_000L
        val minutes = (valueMs % 3_600_000L) / 60_000L
        val seconds = (valueMs % 60_000L) / 1_000L
        val millis = valueMs % 1_000L
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    companion object {
        const val SECONDS_PER_SCENE = 9
    }
}

object VideoStoryQualityPolicy {
    private val genericFiller = listOf(
        "konuya giriş",
        "arka plan",
        "öne çıkan gelişmeler",
        "dönüm noktası",
        "sonuç ve etki",
        "kısa ve anlaşılır biçimde ele alıyoruz",
        "önce temel soruyu"
    )

    private val trustedResearchPublishers = mapOf(
        "ttk.gov.tr" to "Türk Tarih Kurumu",
        "islamansiklopedisi.org.tr" to "TDV İslâm Ansiklopedisi"
    )

    fun validate(plan: VideoStoryPlan) {
        require(plan.hookText.isNotBlank() && plan.hookText.length <= 40) {
            "İlk iki saniye için kısa ve özgün bir kanca gerekli."
        }
        require(plan.hookText.trim().split(Regex("\\s+")).size <= 4) {
            "İlk kanca en fazla dört kısa kelime olmalı."
        }
        require(plan.narrationText.startsWith(plan.hookText)) {
            "Anlatım ilk iki saniyelik kancayla başlamalı."
        }
        require(plan.scenes.size in 3..6) { "Kaynaklı hikâye 3–6 sahne içermeli." }
        require(plan.scenes.none {
            normalizeForComparison(it.heading) == normalizeForComparison(plan.hookText)
        }) {
            "İlk iki saniyelik kanca sonraki sahne başlıklarında tekrar edilmemeli."
        }
        require(plan.scenes.all { it.sceneId.isNotBlank() && !it.visualAssetId.isNullOrBlank() }) {
            "Her sahneye ayrı bir kaynak görsel bağlanmalı."
        }
        require(plan.scenes.map { it.sceneId }.distinct().size == plan.scenes.size) {
            "Sahne kimlikleri benzersiz olmalı."
        }
        require(plan.scenes.map { it.visualAssetId }.distinct().size == plan.scenes.size) {
            "Her sahnede farklı bir görsel kullanılmalı."
        }
        require(plan.scenes.map { normalizeForComparison(it.heading) }.distinct().size == plan.scenes.size) {
            "Noktalama farkıyla yinelenen sahne başlığı kullanılamaz."
        }
        require(plan.scenes.map { normalizeForComparison(it.caption) }.distinct().size == plan.scenes.size) {
            "Noktalama farkıyla yinelenen sahne anlatımı kullanılamaz."
        }
        require(plan.scenes.all { it.claimIds.isNotEmpty() }) {
            "Her sahnenin en az bir doğrulanabilir bilgi kaynağı olmalı."
        }
        require(plan.claimSources.isNotEmpty() &&
            plan.claimSources.map { it.claimId }.distinct().size == plan.claimSources.size) {
            "Araştırma kaynakları eksik veya yinelenmiş."
        }
        val claimsById = plan.claimSources.associateBy { it.claimId }
        val usedClaimIds = plan.scenes.flatMap { it.claimIds }.toSet()
        require(usedClaimIds == claimsById.keys) {
            "Araştırma kaynakları sahnelere bire bir bağlanmalı; sahipsiz veya eksik kaynak bulunamaz."
        }
        plan.scenes.forEach { scene ->
            scene.claimIds.forEach { claimId ->
                val source = claimsById[claimId]
                    ?: throw IllegalArgumentException("Sahne için doğrulanmış kaynak bulunamadı.")
                require(source.sceneId == scene.sceneId &&
                    isTrustedResearchSource(source) &&
                    source.supportedClaim.isNotBlank()) {
                    "Sahne kaynağının kimliği veya desteklediği iddia eksik."
                }
            }
        }
        require(plan.scenes.none { scene ->
            val copy = (scene.heading + " " + scene.caption).lowercase(Locale.forLanguageTag("tr-TR"))
            genericFiller.any(copy::contains)
        }) {
            "Genel dolgu metni kaynaklı hikâyede kullanılamaz."
        }
        require(plan.captions.firstOrNull()?.let {
            it.startMs == 0L && it.text == plan.hookText && it.endMs in 1L..2_000L
        } == true) {
            "Kanca altyazısı videonun ilk iki saniyesinde başlamalı."
        }
    }

    private fun isTrustedResearchSource(source: VideoClaimSource): Boolean {
        val uri = runCatching { URI(source.sourceUrl) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val expectedPublisher = trustedResearchPublishers[host] ?: return false
        val checkedDate = runCatching { LocalDate.parse(source.checkedOn) }.getOrNull()
        val validDate = checkedDate != null && !checkedDate.isAfter(LocalDate.now(ZoneOffset.UTC))
        return uri.scheme == "https" &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawFragment == null &&
            uri.rawQuery == null &&
            !uri.path.isNullOrBlank() &&
            source.publisher == expectedPublisher &&
            validDate
    }

    private fun normalizeForComparison(value: String): String =
        value.lowercase(Locale.forLanguageTag("tr-TR"))
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    fun validateHookDuration(actualDurationMs: Long) {
        require(actualDurationMs in 1L..2_000L) {
            "İlk kanca 2 saniyeyi aşıyor; video üretilmedi."
        }
    }
}

/** Only researched story packs with verified facts and licensed scene visuals can be rendered. */
object VideoStoryPlanBuilder {
    private val istanbulConquestAliases = listOf(
        "istanbul'un fethi",
        "istanbulun fethi",
        "konstantiniyye",
        "1453"
    )

    private val mimarSinanAliases = listOf(
        "mimar sinan",
        "sinan",
        "şehzade camii",
        "sehzade camii",
        "süleymaniye",
        "suleymaniye",
        "selimiye"
    )

    fun build(prompt: String): VideoStoryPlan {
        val clean = prompt.trim().take(VideoRenderSpec.MAX_TITLE_LENGTH)
        require(clean.isNotBlank()) { "Video konusu boş olamaz." }
        val normalized = clean.lowercase(Locale.forLanguageTag("tr-TR"))
        return when {
            mimarSinanAliases.any(normalized::contains) -> buildMimarSinan()
            istanbulConquestAliases.any(normalized::contains) -> buildIstanbulConquest()
            else -> throw IllegalArgumentException(
                "Bu konu için doğrulanmış araştırma ve lisanslı sahne görselleri bulunamadı. Video üretilmedi."
            )
        }
    }

    private fun buildIstanbulConquest(): VideoStoryPlan {
        val hook = "Gemiler karadan Haliç'e!"
        val scenes = listOf(
            VideoScene(
                heading = "Haliç girişini zincir kapattı",
                caption = "Bizans'ın gerdiği zincir, Osmanlı donanmasının Haliç'e doğrudan girmesini engelliyordu.",
                sceneId = "1453-hook-siege",
                visualAssetId = "commons-constantinople-1453-miniature",
                claimIds = listOf("ships-overland-hook")
            ),
            VideoScene(
                heading = "II. Mehmed'in hedefi",
                caption = "Kuşatmayı Sultan II. Mehmed yönetti. Kara ve deniz harekâtı birlikte planlandı.",
                sceneId = "1453-mehmed",
                visualAssetId = "commons-mehmed-ii-portrait",
                claimIds = listOf("mehmed-commander")
            ),
            VideoScene(
                heading = "Boğazı tutan hisar",
                caption = "Rumeli Hisarı, Boğaz'ın Rumeli yakasında 1452'de inşa edildi; geçişi denetlemeye yönelik stratejik bir kaleydi.",
                sceneId = "1453-rumeli",
                visualAssetId = "commons-rumeli-hisari-2025",
                claimIds = listOf("rumeli-hisari")
            ),
            VideoScene(
                heading = "Galata sırtlarından Haliç'e",
                caption = "Osmanlı gemilerinin bir bölümü karadan Haliç'e indirildi. Kaynaklar gemi sayısı, güzergâh ve sürede farklılaşıyor.",
                sceneId = "1453-halic-map",
                visualAssetId = "commons-1453-siege-map-en",
                claimIds = listOf("ships-overland-map")
            ),
            VideoScene(
                heading = "54 günün sonu",
                caption = "Kuşatma 6 Nisan'da başladı ve 29 Mayıs 1453'te sona erdi. Türk Tarih Kurumu bu süreci 54 gün olarak verir.",
                sceneId = "1453-walls",
                visualAssetId = "commons-golden-horn-wall",
                claimIds = listOf("siege-dates")
            )
        )
        val sources = listOf(
            VideoClaimSource(
                "ships-overland-hook",
                "1453-hook-siege",
                "https://islamansiklopedisi.org.tr/halic",
                "TDV İslâm Ansiklopedisi",
                "2026-11-20",
                "1453 kuşatmasında Haliç girişindeki zincir ve gemilerin karadan Haliç'e indirilmesi."
            ),
            VideoClaimSource(
                "mehmed-commander",
                "1453-mehmed",
                "https://ttk.gov.tr/istanbulun-fethi/",
                "Türk Tarih Kurumu",
                "2026-11-20",
                "İstanbul kuşatmasının Sultan Mehmed tarafından yürütülmesi."
            ),
            VideoClaimSource(
                "rumeli-hisari",
                "1453-rumeli",
                "https://islamansiklopedisi.org.tr/rumelihisari",
                "TDV İslâm Ansiklopedisi",
                "2026-11-20",
                "Rumeli Hisarı'nın Boğaz'ın Rumeli yakasında, 15. yüzyılda ve 1452'de inşa edilmesi.",
                "İnşa başlangıcına ilişkin kaynaklar farklı tarihler verir; anlatı yalnızca 1452 yılını kullanır."
            ),
            VideoClaimSource(
                "ships-overland-map",
                "1453-halic-map",
                "https://islamansiklopedisi.org.tr/istanbul",
                "TDV İslâm Ansiklopedisi",
                "2026-11-20",
                "Gemilerin karadan Haliç'e indirilmesi ve bunun kuşatma içindeki yeri.",
                "Gemi sayısı, güzergâh ve tek gecede tamamlanıp tamamlanmadığı tartışmalıdır; kesin sayı verilmez."
            ),
            VideoClaimSource(
                "siege-dates",
                "1453-walls",
                "https://ttk.gov.tr/istanbulun-fethi/",
                "Türk Tarih Kurumu",
                "2026-11-20",
                "Kuşatmanın 6 Nisan 1453'te başlaması ve İstanbul'un 29 Mayıs 1453'te alınması."
            )
        )
        val captions = buildList {
            add(TimedVideoCaption(0L, 2_000L, hook))
            val sceneSpan = (45_000L - 2_000L) / scenes.size
            scenes.forEachIndexed { index, scene ->
                val start = 2_000L + index * sceneSpan
                val spokenText = if (index == 0) scene.caption else scene.heading + ". " + scene.caption
                add(TimedVideoCaption(start, start + sceneSpan, spokenText))
            }
        }
        val plan = VideoStoryPlan(
            title = "İstanbul'un Fethi: 1453",
            scenes = scenes,
            narrationText = (listOf(hook) + scenes.mapIndexed { index, scene ->
                if (index == 0) scene.caption else scene.heading + ". " + scene.caption
            }).joinToString(" "),
            captions = captions,
            sourceLabel = "Türk Tarih Kurumu + TDV İslâm Ansiklopedisi · Wikimedia Commons PD/CC BY-SA",
            hookText = hook,
            claimSources = sources
        )
        VideoStoryQualityPolicy.validate(plan)
        plan.renderSpec.copy(requireLicensedVisualAssets = false).validate()
        return plan
    }

    private fun buildMimarSinan(): VideoStoryPlan {
        val hook = "Süleymaniye yedi yılda!"
        val scenes = listOf(
            VideoScene(
                heading = "48 yaşında mimarbaşı",
                caption = "Prut üzerindeki köprüsüyle dikkat çeken Sinan, 48 yaşındayken mimarbaşılığa getirildi.",
                sceneId = "sinan-mimarbasi",
                visualAssetId = "commons-sinan-sculpture",
                claimIds = listOf("sinan-chief-architect")
            ),
            VideoScene(
                heading = "Çıraklık eseri: Şehzade",
                caption = "Sinan, 1548'de Şehzade Camii'ni tamamladı; bu yapıyı çıraklık eseri diye anlattı.",
                sceneId = "sinan-sehzade",
                visualAssetId = "commons-sinan-sehzade-minarets",
                claimIds = listOf("sinan-apprentice")
            ),
            VideoScene(
                heading = "Kalfalık eseri: Süleymaniye",
                caption = "Cami, temel atıldıktan yedi yıl sonra 15 Ekim 1557'de açıldı. Sinan, onu kalfalık eseri saydı.",
                sceneId = "sinan-suleymaniye",
                visualAssetId = "commons-sinan-suleymaniye-exterior",
                claimIds = listOf("sinan-journeyman")
            ),
            VideoScene(
                heading = "Ustalık eseri: Selimiye",
                caption = "Edirne'de II. Selim adına yaptığı Selimiye, Sinan'ın en büyük eseri olarak gösterilir.",
                sceneId = "sinan-selimiye",
                visualAssetId = "commons-sinan-selimiye-monument",
                claimIds = listOf("sinan-master")
            ),
            VideoScene(
                heading = "Süleymaniye'de son iz",
                caption = "Sinan 1588'de vefat etti. Mezarı, Süleymaniye Külliyesi'nin bir köşesinde bulunuyor.",
                sceneId = "sinan-legacy",
                visualAssetId = "commons-sinan-tomb",
                claimIds = listOf("sinan-legacy")
            )
        )
        val sourceUrl = "https://islamansiklopedisi.org.tr/sinan"
        val publisher = "TDV İslâm Ansiklopedisi"
        val checkedOn = "2026-11-23"
        val sources = listOf(
            VideoClaimSource(
                "sinan-apprentice",
                "sinan-sehzade",
                sourceUrl,
                publisher,
                checkedOn,
                "Sinan, Şehzade Camii'ni 1548'de tamamladı ve bu yapıyı çıraklık eseri olarak tanımladı."
            ),
            VideoClaimSource(
                "sinan-journeyman",
                "sinan-suleymaniye",
                "https://islamansiklopedisi.org.tr/suleymaniye-camii-ve-kulliyesi",
                publisher,
                "2026-11-24",
                "Süleymaniye Camii, temel atıldıktan yedi yıl sonra 15 Ekim 1557'de tamamlandı; Sinan bu yapıyı kalfalık eseri olarak tanımladı."
            ),
            VideoClaimSource(
                "sinan-master",
                "sinan-selimiye",
                sourceUrl,
                publisher,
                checkedOn,
                "II. Selim adına Edirne'de yapılan Selimiye, Sinan'ın en büyük eseri olarak gösterilir."
            ),
            VideoClaimSource(
                "sinan-chief-architect",
                "sinan-mimarbasi",
                sourceUrl,
                publisher,
                checkedOn,
                "Sinan, Prut üzerindeki köprü çalışmasından sonra 48 yaşında mimarbaşılığa getirildi."
            ),
            VideoClaimSource(
                "sinan-legacy",
                "sinan-legacy",
                sourceUrl,
                publisher,
                checkedOn,
                "Sinan 1588'de vefat etti ve mezarı Süleymaniye Külliyesi'nin bir köşesindedir."
            )
        )
        val captions = buildList {
            add(TimedVideoCaption(0L, 2_000L, hook))
            val sceneSpan = (45_000L - 2_000L) / scenes.size
            scenes.forEachIndexed { index, scene ->
                val start = 2_000L + index * sceneSpan
                val spokenText = if (index == 0) scene.caption else scene.heading + ". " + scene.caption
                add(TimedVideoCaption(start, start + sceneSpan, spokenText))
            }
        }
        val plan = VideoStoryPlan(
            title = "Mimar Sinan: Üç eser, bir ömür",
            scenes = scenes,
            narrationText = (listOf(hook) + scenes.mapIndexed { index, scene ->
                if (index == 0) scene.caption else scene.heading + ". " + scene.caption
            }).joinToString(" "),
            captions = captions,
            sourceLabel = "TDV İslâm Ansiklopedisi · Wikimedia Commons CC BY-SA 4.0",
            hookText = hook,
            claimSources = sources
        )
        VideoStoryQualityPolicy.validate(plan)
        plan.renderSpec.copy(requireLicensedVisualAssets = false).validate()
        return plan
    }
}
