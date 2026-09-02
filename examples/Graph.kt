// graph — directed edges over a small corpus, and delete cascade.
//
// Three documents (ga, gb, gc) linked by a `parent_of` relation, plus
// one edge pointing at `gd` which never exists as a document (dangling
// edges are allowed), and a weighted `route` relation. Demonstrates
// neighbors (key order), in-neighbors, weighted neighbors, BFS
// traverse at 1 and 2 hops (cycle-safe), and the delete cascade:
// deleting a key removes its edges in the same transaction — deleting
// the never-a-document `gd` still drops the `gb -> gd` edge (spec
// §4.8/§4.11).
//
// Run: ./gradlew exampleGraph
package corvid.examples

import corvid.openMemory

private fun show(label: String, keys: List<ByteArray>) {
    println("%-36s [%s]".format(label, keys.joinToString(" ") { String(it) }))
}

fun main() {
    openMemory().use { db ->
        val nodes = db.collection("nodes")

        for (key in listOf("ga", "gb", "gc")) {
            nodes.insert(key.toByteArray(), mapOf("n" to key))
        }

        nodes.link("ga".toByteArray(), "parent_of", "gb".toByteArray())
        nodes.link("ga".toByteArray(), "parent_of", "gc".toByteArray())
        nodes.link("gb".toByteArray(), "parent_of", "gd".toByteArray()) // gd never a document
        nodes.linkWeighted("ga".toByteArray(), "route", "gb".toByteArray(), 2.5)
        nodes.linkWeighted("ga".toByteArray(), "route", "gd".toByteArray(), 0.75)

        val ga = "ga".toByteArray()
        val gb = "gb".toByteArray()

        show("neighbors(ga)", nodes.neighbors(ga, "parent_of"))
        show("in_neighbors(gb)", nodes.inNeighbors(gb, "parent_of"))
        val routes = nodes.neighborsWeighted(ga, "route")
            .joinToString(" ") { r -> "%s=%.2f".format(String(r.key), r.weight) }
        println("%-36s [%s]".format("routes from ga (weighted):", routes))
        show("traverse(ga, 1 hop)", nodes.traverse(ga, "parent_of", 1))
        show("traverse(ga, 2 hops)", nodes.traverse(ga, "parent_of", 2))

        // Delete cascade: remove gc (a document) and gd (never a document).
        println("delete gc: existed = " + nodes.delete("gc".toByteArray()))
        val existedGd = nodes.delete("gd".toByteArray())
        println("delete gd: existed = $existedGd (never a document; its edges still cascade)")

        show("neighbors(ga) after deletes", nodes.neighbors(ga, "parent_of"))
        show("neighbors(gb) after deletes", nodes.neighbors(gb, "parent_of"))
        show("traverse(ga, 2 hops) after", nodes.traverse(ga, "parent_of", 2))

        nodes.close()
    }
}
