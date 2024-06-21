package com.spkdev.echomate

import android.content.Context
import android.content.SharedPreferences

class sharedInfo {

    companion object {
        private lateinit var sharedPreferences: SharedPreferences

        fun initialize(context: Context) {
            sharedPreferences = context.getSharedPreferences("MySharedPrefs", Context.MODE_PRIVATE)
        }


        fun saveDataString(key:String, textToSave: String){
            val editor = sharedPreferences.edit()

            editor.putString(key, textToSave)
            editor.apply()
        }

        fun getDataString(key: String): String {
            val valueSaved: String? = if (sharedPreferences.contains(key)){
                sharedPreferences.getString(key, "default_value")
            }else{
                "null"
            }


            return valueSaved.toString()
        }

        fun clearPreferences(){
            sharedPreferences.edit().clear().apply()
        }

        fun exists(key: String): Boolean{
            return sharedPreferences.contains(key)
        }
    }


}