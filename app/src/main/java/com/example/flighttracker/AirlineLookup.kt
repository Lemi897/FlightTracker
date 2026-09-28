package com.example.flighttracker
private val CATEGORY_LABELS = mapOf(
    "A1" to "Light aircraft",
    "A2" to "Small aircraft",
    "A3" to "Large aircraft",
    "A5" to "Heavy aircraft",
    "A7" to "Rotorcraft (helicopter)",
    "B1" to "Glider",
    "B6" to "UAV / drone"
)

fun categoryLabelFor(category: String?): String? = CATEGORY_LABELS[category]
data class Airline(val name: String, val country: CountryInfo)

private val KNOWN_AIRLINES = mapOf(
    "KQA" to Airline("Kenya Airways", CountryInfo("Kenya", "KE")),
    "ETH" to Airline("Ethiopian Airlines", CountryInfo("Ethiopia", "ET")),
    "UAE" to Airline("Emirates", CountryInfo("United Arab Emirates", "AE")),
    "BAW" to Airline("British Airways", CountryInfo("United Kingdom", "GB")),
    "AFR" to Airline("Air France", CountryInfo("France", "FR")),
    "KLM" to Airline("KLM Royal Dutch Airlines", CountryInfo("Netherlands", "NL")),
    "QTR" to Airline("Qatar Airways", CountryInfo("Qatar", "QA")),
    "ETD" to Airline("Etihad Airways", CountryInfo("United Arab Emirates", "AE")),
    "RWD" to Airline("RwandAir", CountryInfo("Rwanda", "RW")),
    "PWG" to Airline("Precision Air", CountryInfo("Tanzania", "TZ")),
    "SAA" to Airline("South African Airways", CountryInfo("South Africa", "ZA")),
    "DLH" to Airline("Lufthansa", CountryInfo("Germany", "DE")),
    "SWR" to Airline("Swiss International Air Lines", CountryInfo("Switzerland", "CH")),
    "TVF" to Airline("Transavia France", CountryInfo("France", "FR"))
)

fun airlineCodeFor(callsign: String?): String? =
    callsign?.trim()?.takeWhile { it.isLetter() }?.uppercase()?.takeIf { it.length == 3 }

fun airlineFor(callsign: String?): Airline? =
    airlineCodeFor(callsign)?.let { KNOWN_AIRLINES[it] }

fun airlineNameFor(callsign: String?): String? = airlineFor(callsign)?.name

fun airlineCountryFor(callsign: String?): CountryInfo? = airlineFor(callsign)?.country