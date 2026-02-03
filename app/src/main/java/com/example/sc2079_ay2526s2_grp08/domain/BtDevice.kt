package com.example.sc2079_ay2526s2_grp08.domain

data class BtDevice(
    val name: String?,
    val address: String,
    val bonded: Boolean
) {
    val label: String get() = (name ?: "Unknown") + " ($address)"
}
