package com.example.blindassist.depth

class UnionFind(size: Int) {
    private val parent = IntArray(size) { it }

    fun find(x: Int): Int {
        if (parent[x] != x) {
            parent[x] = find(parent[x])
        }
        return parent[x]
    }

    fun union(x: Int, y: Int) {
        val rootX = find(x)
        val rootY = find(y)
        if (rootX != rootY) {
            parent[rootY] = rootX
        }
    }

    fun groups(): Map<Int, List<Int>> {
        val groupMap = mutableMapOf<Int, MutableList<Int>>()
        for (i in parent.indices) {
            val root = find(i)
            groupMap.getOrPut(root) { mutableListOf() }.add(i)
        }
        return groupMap
    }
}
