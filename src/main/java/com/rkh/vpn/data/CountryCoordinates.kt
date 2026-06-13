package com.rkh.vpn.data

object CountryCoordinates {
    private val countries = mapOf(
        "US" to Triple("United States", 37.0902, -95.7129),
        "CA" to Triple("Canada", 56.1304, -106.3468),
        "DE" to Triple("Germany", 51.1657, 10.4515),
        "NL" to Triple("Netherlands", 52.1326, 5.2913),
        "FR" to Triple("France", 46.2276, 2.2137),
        "GB" to Triple("United Kingdom", 55.3781, -3.4360),
        "UK" to Triple("United Kingdom", 55.3781, -3.4360),
        "FI" to Triple("Finland", 61.9241, 25.7482),
        "SE" to Triple("Sweden", 60.1282, 18.6435),
        "NO" to Triple("Norway", 60.4720, 8.4689),
        "TR" to Triple("Turkey", 38.9637, 35.2433),
        "AE" to Triple("United Arab Emirates", 23.4241, 53.8478),
        "JP" to Triple("Japan", 36.2048, 138.2529),
        "SG" to Triple("Singapore", 1.3521, 103.8198),
        "IN" to Triple("India", 20.5937, 78.9629),
        "RO" to Triple("Romania", 45.9432, 24.9668),
        "PL" to Triple("Poland", 51.9194, 19.1451),
        "CH" to Triple("Switzerland", 46.8182, 8.2275),
        "AT" to Triple("Austria", 47.5162, 14.5501),
        "IT" to Triple("Italy", 41.8719, 12.5674),
        "ES" to Triple("Spain", 40.4637, -3.7492),
        "PT" to Triple("Portugal", 39.3999, -8.2245),
        "BR" to Triple("Brazil", -14.2350, -51.9253),
        "AU" to Triple("Australia", -25.2744, 133.7751),
        "NZ" to Triple("New Zealand", -40.9006, 174.8860),
        "IR" to Triple("Iran", 32.4279, 53.6880)
    )

    fun routeFor(countryCode: String?, ip: String = "", engine: String = "rkh_msp_http_proxy"): SimorghRoute {
        val code = countryCode.orEmpty().uppercase().take(2)
        val c = countries[code]
        return if (c != null) {
            SimorghRoute(engine, code, c.first, ip, c.second, c.third)
        } else {
            SimorghRoute(engine, code, code.ifBlank { "Unknown" }, ip, null, null)
        }
    }
}
