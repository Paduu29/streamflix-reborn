package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

open class FilemoonExtractor : Extractor() {

    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.site"
    override val aliasUrls = listOf("https://bf0skv.org","https://bysejikuar.com","https://moflix-stream.link","https://bysezoxexe.com","https://bysebuho.com","https://filemoon.sx","https://bysekoze.com","https://bysesayeveum.com")

    private var deviceId = ""

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)
        // Regex to match /e/ or /d/ and ID
        val matcher = Regex("""/(e|d)/([a-zA-Z0-9]+)""").find(link) 
            ?: throw Exception("Could not extract video ID or type")
        
        val linkType = matcher.groupValues[1]
        val videoId = matcher.groupValues[2]
        
        val currentDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1)
            ?: throw Exception("Could not extract Base URL")

        Log.i("StreamFlixES", "[Filemoon] Extraction started for: $link")

        // 1. Details
        val detailsUrl = "$currentDomain/api/videos/$videoId/embed/details"
        Log.i("StreamFlixES", "[Filemoon] Details Request: $detailsUrl")
        val details = service.getDetails(detailsUrl)
        val embedFrameUrl = details.embed_frame_url ?: throw Exception("embed_frame_url not found")
        
        val playbackDomain = Regex("""(https?://[^/]+)""").find(embedFrameUrl)?.groupValues?.get(1)
            ?: throw Exception("Could not extract playback domain")
        Log.i("StreamFlixES", "[Filemoon] Playback Domain detected: $playbackDomain")

        // 2. Challenge
        val challengeUrl = "$playbackDomain/api/videos/access/challenge"
        Log.i("StreamFlixES", "[Filemoon] Challenge Request: $challengeUrl")
        val challenge = service.getChallenge(challengeUrl, mapOf(
            "Referer" to embedFrameUrl,
            "Origin" to playbackDomain,
            "User-Agent" to Service.DEFAULT_USER_AGENT
        ))
        
        val challengeId = challenge.challenge_id ?: throw Exception("No challenge_id")
        val nonce = challenge.nonce ?: throw Exception("No nonce")
        
        var viewerId = ""
        Log.i("StreamFlixES", "[Filemoon] Challenge Data: ID=$challengeId, Viewer=$viewerId")

        // 3. Attestation
        val attestation = generateAttestation(nonce)
        val attestUrl = "$playbackDomain/api/videos/access/attest"
        Log.i("StreamFlixES", "[Filemoon] Attest Request: $attestUrl")
        
        val attestPayload: Map<String, kotlin.Any> = mapOf(
            "viewer_id" to viewerId,
            "device_id" to deviceId,
            "challenge_id" to challengeId,
            "nonce" to nonce,
            "signature" to attestation.signature,
            "public_key" to attestation.publicKey,
            "client" to mapOf(
                "user_agent" to Service.DEFAULT_USER_AGENT,
                "architecture" to "x86",
                "bitness" to "64",
                "platform" to "Windows",
                "platform_version" to "10.0.0",
                "pixel_ratio" to 1.0,
                "screen_width" to 1920,
                "screen_height" to 1080,
                "languages" to listOf("en-US")
            ),
            "storage" to mapOf(
                "cookie" to viewerId,
                "local_storage" to viewerId,
                "indexed_db" to "$viewerId:$deviceId",
                "cache_storage" to "$viewerId:$deviceId"
            ),
            "attributes" to mapOf("entropy" to "high")
        )

        val attestResponse = service.attest(attestUrl, attestPayload, mapOf(
            "Referer" to embedFrameUrl,
            "Origin" to playbackDomain,
            "User-Agent" to Service.DEFAULT_USER_AGENT
        ))
        
        val token = attestResponse.token ?: throw Exception("No attest token")
        viewerId = attestResponse.viewer_id ?: viewerId
        deviceId = attestResponse.device_id ?: deviceId
        val confidence = attestResponse.confidence ?: throw Exception("No confidence in response")

        Log.i("StreamFlixES", "[Filemoon] Attest Token obtained (Confidence: $confidence)")

        // 4. Playback
        val playbackUrl = "$playbackDomain/api/videos/$videoId/embed/playback"
        Log.i("StreamFlixES", "[Filemoon] Playback Request: $playbackUrl")
        val playbackPayload: Map<String, kotlin.Any> = mapOf(
            "fingerprint" to mapOf(
                "token" to token,
                "viewer_id" to viewerId,
                "device_id" to deviceId,
                "confidence" to confidence
            )
        )

        val identityCookie = "byse_viewer_id=$viewerId; byse_device_id=$deviceId"
        val playbackHeaders = mutableMapOf(
            "Referer" to embedFrameUrl,
            "Origin" to playbackDomain,
            "X-Embed-Parent" to (if (linkType == "e") link else ""),
            "User-Agent" to Service.DEFAULT_USER_AGENT,
            "Cookie" to identityCookie,
        )

        val playbackResponse = try {
            service.getPlayback(playbackUrl, playbackPayload, playbackHeaders)
        } catch (error: HttpException) {
            if (error.code() != 428) throw error

            Log.i("StreamFlixES", "[Filemoon] Captcha proof required")
            val captchaUrl = "$playbackDomain/api/videos/$videoId/embed/captcha"
            val captcha = service.getCaptcha(captchaUrl, playbackPayload, playbackHeaders)
            val powNonce = captcha.pow_nonce ?: throw Exception("No captcha PoW nonce")
            val powDifficulty = captcha.pow_difficulty
                ?: throw Exception("No captcha PoW difficulty")
            val powToken = captcha.pow_token ?: throw Exception("No captcha PoW token")
            val solution = solveProofOfWork(powNonce, powDifficulty)

            Log.i("StreamFlixES", "[Filemoon] Captcha proof solved: $solution")
            val verifyUrl = "$playbackDomain/api/videos/$videoId/embed/captcha/verify"
            val verifyPayload: Map<String, kotlin.Any> = mapOf(
                "pow_token" to powToken,
                "solution" to solution,
                "fingerprint" to (playbackPayload["fingerprint"]
                    ?: throw Exception("Missing fingerprint")),
            )
            val verified = service.verifyCaptcha(verifyUrl, verifyPayload, playbackHeaders)
            val captchaToken = verified.token ?: throw Exception("No verified captcha token")

            service.getPlayback(
                playbackUrl,
                playbackPayload,
                playbackHeaders + ("X-Captcha-Token" to captchaToken),
            )
        }

        val playbackData = playbackResponse.playback ?: throw Exception("No playback data")
        Log.i("StreamFlixES", "[Filemoon] Decrypting data...")
        val decryptedJson = decryptPlayback(playbackData)
        
        val jsonObject = JSONObject(decryptedJson)
        val sources = jsonObject.optJSONArray("sources")
            ?: throw Exception("No sources found")
        val sourceUrl = sources.getJSONObject(0).getString("url")

        Log.i("StreamFlixES", "[Filemoon] SOURCE FOUND: $sourceUrl")

        return Video(
            source = sourceUrl,
            headers = mapOf(
                "Referer" to embedFrameUrl,
                "User-Agent" to Service.DEFAULT_USER_AGENT,
                "Origin" to playbackDomain
            )
        )
    }

    private fun generateAttestation(nonce: String): Attestation {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGenerator.generateKeyPair()
        val privateKey = keyPair.private
        val publicKey = keyPair.public as ECPublicKey

        // JWK coordinates
        val x = Base64.encodeToString(publicKey.w.affineX.toByteArray().stripLeadingZero(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val y = Base64.encodeToString(publicKey.w.affineY.toByteArray().stripLeadingZero(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val signatureObj = Signature.getInstance("SHA256withECDSA")
        signatureObj.initSign(privateKey)
        signatureObj.update(nonce.toByteArray())
        val derSignature = signatureObj.sign()

        // Convert DER to Raw (r + s)
        val rawSignature = derToRawSignature(derSignature)
        val encodedSignature = Base64.encodeToString(rawSignature, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val jwk = mapOf(
            "alg" to "ES256",
            "crv" to "P-256",
            "ext" to true,
            "key_ops" to listOf("verify"),
            "kty" to "EC",
            "x" to x,
            "y" to y
        )

        return Attestation(encodedSignature, jwk)
    }

    private fun derToRawSignature(der: ByteArray): ByteArray {
        // Simple DER parser for ECDSA signature (SEQUENCE { r INTEGER, s INTEGER })
        var offset = 2
        val rLen = der[offset + 1].toInt()
        val r = der.copyOfRange(offset + 2, offset + 2 + rLen).stripLeadingZero()
        offset += 2 + rLen
        val sLen = der[offset + 1].toInt()
        val s = der.copyOfRange(offset + 2, offset + 2 + sLen).stripLeadingZero()

        val raw = ByteArray(64)
        System.arraycopy(r, 0, raw, 32 - r.size, r.size)
        System.arraycopy(s, 0, raw, 64 - s.size, s.size)
        return raw
    }

    private fun ByteArray.stripLeadingZero(): ByteArray {
        return if (this.isNotEmpty() && this[0] == 0.toByte()) {
            this.copyOfRange(1, this.size)
        } else {
            this
        }
    }

    data class Attestation(val signature: String, val publicKey: Map<String, kotlin.Any>)

    private fun decryptPlayback(data: PlaybackData): String {
        val iv = Base64.decode(data.iv, Base64.URL_SAFE)
        val payload = Base64.decode(data.payload, Base64.URL_SAFE)
        val version = data.version?.toIntOrNull()
        val keyIndexes = if (
            version != null && version in 1 until 31 &&
            version <= data.key_parts.size && 31 - version <= data.key_parts.size
        ) {
            listOf(version - 1, 30 - version)
        } else {
            listOf(0, 1)
        }
        val p1 = Base64.decode(data.key_parts[keyIndexes[0]], Base64.URL_SAFE)
        val p2 = Base64.decode(data.key_parts[keyIndexes[1]], Base64.URL_SAFE)
        
        val key = ByteArray(p1.size + p2.size)
        System.arraycopy(p1, 0, key, 0, p1.size)
        System.arraycopy(p2, 0, key, p1.size, p2.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(key, "AES")
        
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        
        val decryptedBytes = cipher.doFinal(payload)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    /** Exact proof-of-work routine used by the current pow-DEJGtdh2.js bundle. */
    private suspend fun solveProofOfWork(nonce: String, difficulty: Int): String {
        if (difficulty <= 0) return "0"
        val state = IntArray(4)
        val memory = IntArray(POW_MEMORY_WORDS)
        val prefix = "$nonce:"
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var solution = 0

        while (android.os.SystemClock.elapsedRealtime() - startedAt < POW_TIMEOUT_MILLIS) {
            repeat(POW_BATCH_SIZE) {
                if (powLeadingZeroBits(prefix, solution, state, memory) >= difficulty) {
                    return solution.toString()
                }
                solution++
            }
            kotlinx.coroutines.yield()
        }
        throw Exception("Timed out solving Filemoon captcha proof")
    }

    private fun powLeadingZeroBits(
        prefix: String,
        solution: Int,
        state: IntArray,
        memory: IntArray,
    ): Int {
        state[0] = 1779033703
        state[1] = -1150833019 // 3144134277 unsigned
        state[2] = 1013904242
        state[3] = -1521486534 // 2773480762 unsigned

        fun absorb(value: Int) {
            state[0] += value and 0xff
            state[0] = Integer.rotateLeft(state[0], 7)
            powQuarterRound(state)
        }

        prefix.forEach { absorb(it.code) }
        solution.toString().forEach { absorb(it.code) }
        repeat(8) { powQuarterRound(state) }

        for (index in memory.indices) {
            powQuarterRound(state)
            memory[index] = state[0] xor state[2]
        }

        repeat(POW_MEMORY_PASSES) {
            for (index in memory.indices) {
                val lookup = memory[index] and (POW_MEMORY_WORDS - 1)
                var mixed = memory[index] + memory[lookup]
                mixed = Integer.rotateLeft(mixed, 13)
                mixed = mixed xor (memory[(index + 1) and (POW_MEMORY_WORDS - 1)] * -1640531535)
                memory[index] = mixed
                state[0] = state[0] xor mixed
                powQuarterRound(state)
            }
        }

        var zeroBits = 0
        val blockSize = POW_MEMORY_WORDS / 8
        repeat(8) { block ->
            powQuarterRound(state)
            var value = state[0]
            val start = block * blockSize
            repeat(blockSize) { offset ->
                val word = memory[start + offset]
                value += word
                value = Integer.rotateLeft(value, 5)
                value = value xor (word * -2048144777)
            }
            val output = value xor state[2]
            if (output == 0) {
                zeroBits += 32
            } else {
                return zeroBits + Integer.numberOfLeadingZeros(output)
            }
        }
        return zeroBits
    }

    private fun powQuarterRound(state: IntArray) {
        state[0] += state[1]
        state[3] = Integer.rotateLeft(state[3] xor state[0], 16)
        state[2] += state[3]
        state[1] = Integer.rotateLeft(state[1] xor state[2], 12)
        state[0] += state[1]
        state[3] = Integer.rotateLeft(state[3] xor state[0], 8)
        state[2] += state[3]
        state[1] = Integer.rotateLeft(state[1] xor state[2], 7)
    }

    open class Any(hostUrl: String) : FilemoonExtractor() {
        override val mainUrl = hostUrl
    }

    private interface Service {
        @GET
        suspend fun getDetails(@Url url: String): DetailsResponse

        @POST
        suspend fun getChallenge(@Url url: String, @HeaderMap headers: Map<String, String>): ChallengeResponse

        @JvmSuppressWildcards
        @POST
        suspend fun attest(@Url url: String, @Body body: Map<String, kotlin.Any>, @HeaderMap headers: Map<String, String>): AttestResponse

        @JvmSuppressWildcards
        @POST
        suspend fun getPlayback(@Url url: String, @Body body: Map<String, kotlin.Any>, @HeaderMap headers: Map<String, String>): PlaybackResponse

        @JvmSuppressWildcards
        @POST
        suspend fun getCaptcha(@Url url: String, @Body body: Map<String, kotlin.Any>, @HeaderMap headers: Map<String, String>): CaptchaChallenge

        @JvmSuppressWildcards
        @POST
        suspend fun verifyCaptcha(@Url url: String, @Body body: Map<String, kotlin.Any>, @HeaderMap headers: Map<String, String>): CaptchaVerifyResponse

        companion object {
            const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"

            fun build(baseUrl: String): Service {
                val cookieJar = object : okhttp3.CookieJar {
                    private val cookieStore = HashMap<String, List<okhttp3.Cookie>>()
                    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                        cookieStore[url.host] = cookies
                    }
                    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                        return cookieStore[url.host] ?: ArrayList()
                    }
                }

                val client = OkHttpClient.Builder()
                    .cookieJar(cookieJar)
                    .dns(DnsResolver.doh).build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }
    }

    data class DetailsResponse(val embed_frame_url: String?)
    data class ChallengeResponse(val challenge_id: String?, val nonce: String?, val viewer_hint: String?)
    data class AttestResponse(val token: String?, val viewer_id: String?, val device_id: String?, val confidence: Double?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class CaptchaChallenge(
        val pow_nonce: String?,
        val pow_difficulty: Int?,
        val pow_token: String?,
    )
    data class CaptchaVerifyResponse(val status: String?, val token: String?)
    data class PlaybackData(
        val iv: String,
        val payload: String,
        val key_parts: List<String>,
        val version: String?,
        )

    private companion object {
        const val POW_MEMORY_WORDS = 512
        const val POW_MEMORY_PASSES = 2
        const val POW_BATCH_SIZE = 1024
        const val POW_TIMEOUT_MILLIS = 20_000L
    }
}
