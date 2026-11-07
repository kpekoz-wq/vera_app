package com.vera.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Data class mapping response fields from the NHTSA vPIC vehicle decode API.
 * Maps the response fields: Make, Model, and ModelYear.
 */
@Serializable
data class VehicleInfo(
    @SerialName("Make") val make: String? = null,
    @SerialName("Model") val model: String? = null,
    @SerialName("ModelYear") val modelYear: String? = null,
)

@Serializable
private data class NhtsaResponse(
    @SerialName("Results") val results: List<VehicleInfo>? = null,
)

private val jsonParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Fetches and parses vehicle information (Make, Model, ModelYear) from the public NHTSA vPIC API.
 *
 * @param vin The 17-character Vehicle Identification Number.
 * @return A populated [VehicleInfo] object containing Make, Model, and ModelYear, or null on error.
 */
suspend fun fetchAndParseVehicleInfo(vin: String): VehicleInfo? = withContext(Dispatchers.IO) {
    val cleanVin = vin.trim().uppercase()
    if (cleanVin.length < 11) {
        Log.w("NhtsaVinDecoder", "Invalid VIN length provided: '$cleanVin'")
        return@withContext null
    }

    val urlString = "https://vpic.nhtsa.dot.gov/api/vehicles/decodevinvaluesextended/$cleanVin?format=json"
    var connection: HttpURLConnection? = null

    try {
        val url = URL(urlString)
        connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
        }

        val responseCode = connection.responseCode
        if (responseCode in 200..299) {
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val parsedResponse = jsonParser.decodeFromString<NhtsaResponse>(responseText)
            val info = parsedResponse.results?.firstOrNull()

            if (info != null && (!info.make.isNullOrBlank() || !info.model.isNullOrBlank() || !info.modelYear.isNullOrBlank())) {
                Log.i("NhtsaVinDecoder", "Successfully decoded VIN $cleanVin: ${info.make} ${info.model} ${info.modelYear}")
                return@withContext info
            } else {
                Log.w("NhtsaVinDecoder", "NHTSA API returned empty or invalid results for VIN $cleanVin")
            }
        } else {
            Log.e("NhtsaVinDecoder", "NHTSA vPIC API HTTP request failed with status code: $responseCode")
        }
    } catch (e: Exception) {
        Log.e("NhtsaVinDecoder", "Exception occurred while fetching vehicle info for VIN $cleanVin", e)
    } finally {
        connection?.disconnect()
    }

    return@withContext null
}
