package com.shtong.app

object Contract {
    const val VERSION = "account-access-baseline.v1"
    val ROLES = setOf("USER", "TEACHER_ADMIN", "STUDENT_ADMIN", "MEDICAL_INSURANCE_ADMIN", "SYSTEM_ADMIN")
    val SYSTEM_MODULES = listOf("system.accounts", "system.graduates", "system.authorization", "system.organization", "system.audit")
}
