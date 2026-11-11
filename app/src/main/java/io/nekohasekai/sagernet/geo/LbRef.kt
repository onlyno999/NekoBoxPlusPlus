package io.nekohasekai.sagernet.geo

import android.content.Context
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase

/**
 * Reference to a load-balanced country of one group, encoded into
 * [io.nekohasekai.sagernet.database.RuleEntity.outbound] (a Long
 * where 0 = proxy, -1 = bypass, -2 = block, positive = profile id).
 * LB references live in a reserved negative band:
 * encode = -(1_000_000 + groupId * 676 + countryNumber), with
 * countryNumber = (c0 - 'A') * 26 + (c1 - 'A') for the 2-letter code.
 */
object LbRef {

    private const val BASE = 1_000_000L
    private const val CC_SPACE = 676L

    fun encode(groupId: Long, countryCode: String): Long {
        val cc = countryCode.uppercase()
        require(cc.length == 2) { "country code must be 2 letters" }
        val num = (cc[0] - 'A') * 26 + (cc[1] - 'A')
        return -(BASE + groupId * CC_SPACE + num)
    }

    fun isLb(outbound: Long): Boolean = outbound <= -BASE

    fun groupId(outbound: Long): Long {
        val v = -outbound - BASE
        return v / CC_SPACE
    }

    fun countryCode(outbound: Long): String {
        val num = ((-outbound - BASE) % CC_SPACE).toInt()
        return "${'A' + num / 26}${'A' + num % 26}"
    }

    /** sing-box outbound tag for an LB country. */
    fun tag(groupId: Long, countryCode: String): String =
        "lb-$groupId-${countryCode.uppercase()}"

    fun tagFor(outbound: Long): String = tag(groupId(outbound), countryCode(outbound))

    fun label(countryCode: String): String =
        "负载均衡 · " + GeoStore.countryTitle(countryCode)

    data class Option(
        val group: ProxyGroup,
        val countryCode: String,
        val encoded: Long,
    )

    /** Every (group, country) that currently has at least one profile. */
    fun collectOptions(context: Context): List<Option> {
        val out = mutableListOf<Option>()
        for (group in SagerDatabase.groupDao.allGroups()) {
            if (group.ungrouped) continue
            val countries = SagerDatabase.proxyDao.getByGroup(group.id)
                .mapNotNull { GeoStore.countryFor(context, it.requireBean()) }
                .distinct().sorted()
            for (cc in countries) {
                out.add(Option(group, cc, encode(group.id, cc)))
            }
        }
        return out
    }

    /** Profile ids of the LB country's members inside its group. */
    fun memberIds(outbound: Long): List<Long> {
        val gid = groupId(outbound)
        val cc = countryCode(outbound)
        return SagerDatabase.proxyDao.getByGroup(gid)
            .filter { profile ->
                GeoStore.countryFor(
                    io.nekohasekai.sagernet.ktx.app, profile.requireBean()
                ) == cc
            }
            .map { it.id }
    }
}
