package com.atothei.roadhouse.overlay

/**
 * Delivery apps RoadHouse Overlay recognises by package name.
 * Package names verified 2026-11-23:
 * - Spark: com.walmart.sparkdriver (Uptodown / APKPure listings)
 * - Instacart Shopper: com.instacart.shopper (APKMirror / APKShift)
 * - DoorDash Dasher: com.doordash.driverapp (given)
 * Anything else is still readable — the overlay works over any foreground
 * app, it just won't get a friendly platform name.
 */
object Platforms {
    const val SPARK = "com.walmart.sparkdriver"
    const val DOORDASH = "com.doordash.driverapp"
    const val INSTACART = "com.instacart.shopper"

    private val names = mapOf(
        SPARK to "Spark",
        DOORDASH to "DoorDash",
        INSTACART to "Instacart"
    )

    fun displayName(packageName: String?): String {
        if (packageName.isNullOrBlank()) return "Unknown app"
        return names[packageName] ?: "Other app"
    }

    fun isDeliveryApp(packageName: String?): Boolean =
        packageName != null && names.containsKey(packageName)
}
