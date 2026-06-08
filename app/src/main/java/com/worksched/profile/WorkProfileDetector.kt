package com.worksched.profile

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

class WorkProfileDetector(private val context: Context) {
    private val userManager: UserManager
        get() = context.getSystemService(Context.USER_SERVICE) as UserManager

    fun workProfileHandle(): UserHandle? {
        val me = Process.myUserHandle()
        return userManager.userProfiles.firstOrNull { it != me }
    }

    fun isWorkProfilePresent(): Boolean = workProfileHandle() != null

    fun isWorkProfileQuiet(): Boolean? {
        val handle = workProfileHandle() ?: return null
        return runCatching { userManager.isQuietModeEnabled(handle) }.getOrNull()
    }
}
