package no.nav.platforce.tool.dependencies

object VersionComparator {
    fun compare(
        current: String,
        target: String,
    ): Int {
        val a = split(current)
        val b = split(target)

        val max = maxOf(a.size, b.size)

        for (i in 0 until max) {
            val av = a.getOrElse(i) { VersionPart.Number(0) }
            val bv = b.getOrElse(i) { VersionPart.Number(0) }

            if (av == bv) continue

            return av.compareTo(bv)
        }

        return 0
    }

    private fun split(version: String): List<VersionPart> =
        Regex("""([0-9]+|[A-Za-z]+)""")
            .findAll(version)
            .map {
                val value = it.value

                if (value.all(Char::isDigit)) {
                    VersionPart.Number(value.toInt())
                } else {
                    VersionPart.Text(value.lowercase())
                }
            }.toList()

    private sealed interface VersionPart : Comparable<VersionPart> {
        data class Number(
            val value: Int,
        ) : VersionPart {
            override fun compareTo(other: VersionPart): Int =
                when (other) {
                    is Number -> value.compareTo(other.value)
                    is Text -> -1
                }
        }

        data class Text(
            val value: String,
        ) : VersionPart {
            override fun compareTo(other: VersionPart): Int =
                when (other) {
                    is Text -> value.compareTo(other.value)
                    is Number -> 1
                }
        }
    }
}
