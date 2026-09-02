// geo — points, radius, bbox, nearest-k with real coordinates.
//
// Four German cities stored with their real lat/lon (the [lat, lon]
// array encoding; a {"lat": …, "lon": …} map encodes the same point).
// Distances are haversine kilometres:
//
//   radius 600 km from central Berlin (52.52, 13.40):
//     berlin 0.000000, potsdam 26.621424, hamburg 255.120591,
//     munchen 503.833264 — nearest first, inclusive boundary.
//   bbox (47..55, 5..15): all four, key order, the 0.0 sentinel
//     (a box has no center to measure from).
//   nearest 2: berlin, potsdam — exact haversine order.
//
// These are the same points and tolerances the engine's golden geo
// fixture asserts (~1e-6 km).
//
// Run: ./gradlew exampleGeo
package corvid.examples

import corvid.openMemory

private val cities = listOf(
    Triple("berlin", 52.52, 13.40),
    Triple("potsdam", 52.40, 13.06),
    Triple("hamburg", 53.55, 9.99),
    Triple("munchen", 48.14, 11.58),
)

private fun show(label: String, hits: List<corvid.GeoHit>) {
    val line = hits.joinToString(" ") { h -> "%s %.6fkm".format(String(h.key), h.distanceKm) }
    println("%-34s [%s]".format(label, line))
}

fun main() {
    openMemory().use { db ->
        val places = db.collection("places")

        for ((name, lat, lon) in cities) {
            places.insert(name.toByteArray(), mapOf(
                "name" to name,
                "loc" to listOf(lat, lon), // the [lat, lon] array encoding
            ))
        }
        places.createGeoIndex("loc")

        show("within 600km of Berlin:", places.geoWithinRadius("loc", 52.52, 13.40, 600.0))
        show("bbox 47..55N, 5..15E:", places.geoWithinBBox("loc", 47.0, 5.0, 55.0, 15.0))
        show("nearest 2 to Berlin:", places.geoNearest("loc", 52.52, 13.40, 2))

        places.close()
    }
}
