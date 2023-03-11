package com.osfans.trime.util

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager


class SpvalueStorage private constructor(context: Context) {
    init {
        sp = PreferenceManager.getDefaultSharedPreferences(context)
        editor = sp.edit()
    }

    fun getStringValue(key: String?, defaultValue: String?): String? {
        return sp.getString(key, defaultValue)
    }

    fun getBooleanValue(key: String?, bool: Boolean): Boolean{
        return sp.getBoolean(key, bool)
    }

    fun setStringvalue(key: String?, value: String?) {
        editor.putString(key, value)
        editor.commit()
    }

    fun getInt(key: String?, defaultValue: Int): Int {
        return sp.getInt(key, defaultValue)
    }

    fun setIntValue(key: String?, value: Int) {
        editor.putInt(key, value)
        editor.commit()
    }

    fun setBooleanValue(key: String?, bool: Boolean) {
        editor.putBoolean(key, bool)
        editor.commit()
    }

    companion object {
        private lateinit var sp: SharedPreferences
        private lateinit var editor: SharedPreferences.Editor
        private var instance: SpvalueStorage? = null
        fun getInstance(context: Context): SpvalueStorage? {
            if (instance == null) {
                instance = SpvalueStorage(context)
            }
            return instance
        }
    }
}