package com.spkdev.echomate

import android.content.Context
import android.content.SharedPreferences

class sharedInfo {

    companion object {
        private lateinit var sharedPreferences: SharedPreferences

        fun initialize(context: Context) {
            sharedPreferences = context.getSharedPreferences("MySharedPrefs", Context.MODE_PRIVATE)
        }



        fun getFavorites(): MutableSet<String> {
            return sharedPreferences.getStringSet("Favorites", mutableSetOf())!!.toMutableSet()
        }

        fun addFavorite(name: String) {
            val favs = getFavorites()
            favs.add(name)
            sharedPreferences.edit().putStringSet("Favorites", favs).apply()
        }

        fun removeFavorite(name: String) {
            val favs = getFavorites()
            favs.remove(name)
            sharedPreferences.edit().putStringSet("Favorites", favs).apply()
        }

        fun isFavorite(name: String): Boolean {
            return getFavorites().contains(name)
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

        fun removeKey(key: String){
            sharedPreferences.edit().remove(key).apply()
        }

        fun exists(key: String): Boolean{
            return sharedPreferences.contains(key)
        }
    }


}