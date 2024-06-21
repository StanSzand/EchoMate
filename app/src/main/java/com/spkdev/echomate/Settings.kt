package com.spkdev.echomate

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.activity.ComponentActivity


class Settings: ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        //Image Buttons Initialisation
        val backButton = findViewById<ImageButton>(R.id.goBackButton)
        val resetSetup = findViewById<ImageButton>(R.id.resetSetupButton)
        val changeSetupButton = findViewById<ImageButton>(R.id.confirmChangeSetup)
        val modelChangeButton = findViewById<ImageButton>(R.id.confirmChangeModel)
        val resetModelButton = findViewById<ImageButton>(R.id.resetModelButton)
        val nameChangeButton = findViewById<ImageButton>(R.id.confirmChangeName)
        val spinnerButton = findViewById<ImageButton>(R.id.confirmSpinnerModel)
        val mainActivity = MainActivity()


        // EditText fields Initialisation
        val newSetup = findViewById<EditText>(R.id.newSetup)
        val modelChangeText = findViewById<EditText>(R.id.newModel)
        val nameValue = findViewById<EditText>(R.id.nameSetup)

        //Other Initialisation
        val spinnerChoice = findViewById<Spinner>(R.id.spinnerModels)
        val modelsArray = resources.getStringArray(R.array.models)


        //switch
        val contextSwitch = findViewById<Switch>(R.id.extendedHistorySwitch)



        //back button
        backButton.setOnClickListener {
            finish()
        }


        //changing the name
        nameChangeButton.setOnClickListener {
            val newName = nameValue.text.toString()
            AIBackend.changeName(newName)
            sharedInfo.saveDataString("Name", newName)
            Toast.makeText(this, "Name has been changed to $newName", Toast.LENGTH_SHORT).show()
            nameValue.text.clear()
        }



        //changing the setup
        changeSetupButton.setOnClickListener {
            val newSetupString = newSetup.text.toString().replace("\"", "'")
            AIBackend.changeSetup(newSetupString)
            Toast.makeText(this, "Changed setup", Toast.LENGTH_SHORT).show()
            newSetup.text.clear()
        }

        //resetting the setup
        resetSetup.setOnClickListener {
            AIBackend.resetSetup()
            Toast.makeText(this, "Setup has been reset", Toast.LENGTH_SHORT).show()
        }

        //Model choice from a dropdown list
        spinnerChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelsArray)
        spinnerChoice.onItemSelectedListener = object: OnItemSelectedListener{
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                Log.v("data", modelsArray[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                TODO("Not yet implemented")
            }
        }

        spinnerButton.setOnClickListener {
            if (spinnerChoice.selectedItemPosition == 0){
                Toast.makeText(this@Settings, "Please choose a model first", Toast.LENGTH_LONG).show()
            }else{
                val model = modelsArray[spinnerChoice.selectedItemPosition]
                AIBackend.changeModel(model)
                sharedInfo.saveDataString("Model", model)
                Toast.makeText(this@Settings, "Model $model has been set", Toast.LENGTH_LONG).show()
            }
        }

        //changing the model manually
        modelChangeButton.setOnClickListener {
//            val newModelString = modelChangeText.text.toString()
//            AIBackend.changeModel(newModelString)
//            sharedInfo.saveDataString("Model", newModelString)
//            Toast.makeText(this, "Model has been changed to $newModelString", Toast.LENGTH_SHORT).show()
//            modelChangeText.text.clear()
            Toast.makeText(this@Settings, "This option is disabled for now.", Toast.LENGTH_LONG).show()

        }
        //resetting the model
        resetModelButton.setOnClickListener {
            AIBackend.resetModel()
            Toast.makeText(this, "Model has been reset", Toast.LENGTH_SHORT).show()
        }

        //experimental context switch added
        contextSwitch.setOnClickListener{
            if (contextSwitch.isChecked){
                AIBackend.enableContext(true)
                Toast.makeText(this, "WARNING - this feature is experimental", Toast.LENGTH_LONG).show()
                Toast.makeText(this, "IT WILL NOT WORK PROPERLY ON MOST (IF ANY) MODELS", Toast.LENGTH_LONG).show()
            }else{
                AIBackend.enableContext(false)
            }
        }


        if (sharedInfo.exists("ContextSwitch")){
            val value = sharedInfo.getDataString("ContextSwitch").toBoolean()
            AIBackend.enableContext(value)

            contextSwitch.setChecked(value)
        }else{
            AIBackend.enableContext(false)
        }



    }


}