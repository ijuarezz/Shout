package com.shout

import com.shout.ui.theme.AppTheme
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.CallSuper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults.cardColors
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType.Companion.PrimaryEditable
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.OnTokenCanceledListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs


// CONSTANTS
const val updateFrequency: Long = 10 * 1000   //  secs
const val tooOldDuration: Long = updateFrequency * 60  //  same value but in minutes
const val maxDistance: Float = 20f
const val maxVoteLength: Int = 30
const val pendingLabel = "\u25CF\u25CF\u25CF"


class MainActivity : ComponentActivity() {

    // Classes
    data class VoteToTallyFadeClass(val nVotes: Int, val vote: String, val ageFade: Float)
    data class IdToVoteTimeClass(val vote: String, val timeStamp: Long)
    data class IdVote(val id: String, val vote: String)

    class VoteDbClass (private val voteChannel: Channel<IdVote>){

        private var idToVoteTime = mutableMapOf<String, IdToVoteTimeClass>()
        
        var sortByVote: Boolean = true

        private val mutexVote = Mutex()

        @OptIn(ExperimentalCoroutinesApi::class)
        suspend fun add(){


                while(!voteChannel.isEmpty){

                    // getAll() may need to read the table in between updates
                    mutexVote.withLock {
                        val it = voteChannel.receive()

                        // Log.d("###", "======== VoteDbClass  adding ${it.id} ${it.vote} ")
                        if (idToVoteTime.containsKey(it.id)) idToVoteTime.remove(it.id)
                        idToVoteTime[it.id] = IdToVoteTimeClass(
                            vote = it.vote,
                            timeStamp = System.currentTimeMillis()
                        )
                    }
                }

                // Log.d("###","========   VoteDbClass  finished  ")

        }



        suspend fun getAll(): List<VoteToTallyFadeClass> {

            // Log.d("###","======== VoteDbClass  getAll")
            val votesSummary: MutableMap<String, Int> = HashMap()
            val votesTimestamp: MutableMap<String, Long> = HashMap()


            val votesOutput = mutableListOf<VoteToTallyFadeClass>()
            val tooOld: Long = System.currentTimeMillis() - tooOldDuration

            // This is the only section that needs to block the DB
            mutexVote.withLock {

                //delete old entries by time first
                for (thisVote in idToVoteTime) {  if (thisVote.value.timeStamp < tooOld) idToVoteTime.remove(thisVote.key)  }

                // tally votes
                for (thisVote in idToVoteTime) {

                    val v = thisVote.value.vote

                    var count = votesSummary[v]
                    if (count == null) count = 0
                    votesSummary[v] = count + 1

                    var avgTimestamp = votesTimestamp[v]
                    if (avgTimestamp == null) avgTimestamp = thisVote.value.timeStamp
                    votesTimestamp[v] = (avgTimestamp + thisVote.value.timeStamp) / 2

                }
            }


            // sort by either votes or alphabetically
            val votesSorted: Map<String, Int> =
                if (sortByVote) votesSummary.toList().sortedBy { (_, v) -> v }.reversed().toMap()
                else votesSummary.toList().sortedBy { (k, _) -> k }.toMap()

            // fade based on time
            var ageFade: Float

            for (thisVote in votesSorted) {

                var avgTimestamp = votesTimestamp[thisVote.key] ?: 0L
                avgTimestamp -= tooOld
                ageFade = avgTimestamp.toFloat() / tooOldDuration.toFloat()


                ageFade = (ageFade * 0.8f)+0.2f  // controls transparency 20-100%

                votesOutput.add(
                    VoteToTallyFadeClass(
                    nVotes = thisVote.value,
                    vote = thisVote.key,
                    ageFade = ageFade
                    )
                )
            }

            return votesOutput

        }


    }


    // Variables
    private var updateFrequency10 = 0
    private var firstVote: Boolean = false
    private var myId: String = ""

    var myLat: Double = 0.0
    var myLong: Double = 0.0
    var myVote: String = pendingLabel

    private var beaconVote: String="0"
    private var beaconNearby: Boolean=false

    val voteChannel = Channel<IdVote>(Channel.UNLIMITED)
    var voteDb = VoteDbClass(voteChannel)

    private var listOfVotes = mutableListOf<String>()

    private val updateFrequencyTimer = Timer(true)
    var timerOn = true


    // Nearby & Location
    private val strategy = Strategy.P2P_CLUSTER
    private lateinit var connectionsClient: ConnectionsClient

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {

            val aInfo :  List<String> = info.endpointName.split("#")

            // Log.d("###","Incoming from ${info.endpointName}")

            // Check if 5 fields were received
            if (aInfo.size != 5) {
                // Log.d("###","Received ${aInfo.size} instead of 5 fields received. aInfo: $aInfo")
                return
            }

            val newId: String = aInfo[1]
            val newLat: String = aInfo[2]
            val newLong: String = aInfo[3]

            val newLatD: Double = newLat.toDouble()
            val newLongD: Double = newLong.toDouble()

            // Warning if missing location
            if(myLat == 0.toDouble()){
                Toast.makeText(this@MainActivity, "Location settings error, using approximate values", Toast.LENGTH_LONG).show()
                myLat = newLatD
                myLong = newLongD
            }

            // Check if within max distance
            val newDistance: FloatArray = floatArrayOf(0f)
            Location.distanceBetween(newLatD,newLongD, myLat,myLong,newDistance)
            if (newDistance[0] > maxDistance) return


            val voteStart = newId.length + newLat.length + newLong.length + 5
            val newVote: String = info.endpointName.substring( voteStart, info.endpointName.length)

            // Check for empty strings
            if(newId.isEmpty() or newVote.isEmpty()) return

            // Add new vote
            runBlocking {voteChannel.send(IdVote(newId,newVote))}


        }

        override fun onEndpointLost(endpointId: String) {Log.d("###","Lost  $endpointId")}

    }
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {Log.d("###","Incoming from ${info.endpointName}")}
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {Log.d("###","Result  $endpointId")}
        override fun onDisconnected(endpointId: String) {Log.d("###","Disconnected  $endpointId")}

    }

    private var fusedLocationClient: FusedLocationProviderClient? = null

    //Nearby functions

    @SuppressLint("MissingPermission")
    fun broadcastUpdate() {

        Log.d("###","======== broadcastUpdate")

        // Stop
        runBlocking {
                // Log.d("###", " broadcastUpdate stop")
                connectionsClient.stopAdvertising()
        }

        // Start
        runBlocking {

                // Log.d("###","   broadcastUpdate start")

                val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()

                beaconVote = if (beaconVote=="0") {"1"} else{"0"}

                connectionsClient.startAdvertising(
                    "$beaconVote#$myId#$myLat#$myLong#$myVote",
                    packageName,
                    connectionLifecycleCallback,
                    advertisingOptions
                )
        }


        // Every 10 x cycles get Location
        if (updateFrequency10++ == 10) {  updateFrequency10 = 0

            // Log.d("###","  broadcastUpdate Every 10 x cycles ")
            getLoc()

            }
        }

    // Independent Functions


    @SuppressLint("MissingPermission")
    private fun getLoc(){

        // Get Location
        fusedLocationClient?.getCurrentLocation(
            PRIORITY_HIGH_ACCURACY,
            object : CancellationToken() {
                override fun onCanceledRequested(p0: OnTokenCanceledListener) =
                    CancellationTokenSource().token

                override fun isCancellationRequested() = false
            })
            ?.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    myLat = location.latitude
                    myLong = location.longitude
                    // Log.d("###","== Location: $myLat $myLong")
                }

            }

    }


    private fun checkPermissions() {


        val votePermissions: Array<String>

        if (Build.VERSION.SDK_INT > 30) {
            //Permissions Android 12 (31)
            votePermissions = arrayOf(
                Manifest.permission.NFC,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            )
        } else {
            //Permissions older devices
            votePermissions =
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
            )

        }


        var needPermission = "None"

        votePermissions.forEach {

            if (checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED) needPermission = it
        }


        if (needPermission != "None") {

            val voteRequestPermission =
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->

                    if (isGranted) {

                        recreate()

                    } else {

                        val errMsg = "Cannot start without required permissions"
                        Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()

                        // Reset for next run & exit
                        val packageName = applicationContext.packageName
                        val runtime = Runtime.getRuntime()
                        runtime.exec("pm clear $packageName")

                        finish()

                    }
                }

            voteRequestPermission.launch(needPermission)

        }


    }

    private fun getMyPreferences() {

        val sharedPreference = getSharedPreferences("MyPreferences", MODE_PRIVATE)
        val editor = sharedPreference.edit()

        // Find or create myID
        if (sharedPreference.contains("myId")) {
            myId = sharedPreference.getString("myId", "").toString()
        } else {
            myId = System.currentTimeMillis().toString()
            editor.putString("myId", myId)
            editor.apply()
        }

        // restore old votes

        if (sharedPreference.contains("oldVotes")) {
            val votesInPreferences = sharedPreference.getString("oldVotes", "")

            if (votesInPreferences != null) {
                val votesInPreferencesList = votesInPreferences.split("###")
                listOfVotes = votesInPreferencesList.toMutableList()
            }

        }

        // If first time, add default votes
        if (listOfVotes.isEmpty()) {

            listOfVotes = listOf("Music is too LOUD", "A música está muito ALTA", "संगीत बहुत तेज़ है" ).toMutableList()
            editor.putString("oldVotes", listOfVotes.joinToString("###") { it })
            editor.apply()
        }
        else{

            firstVote= (listOfVotes == listOf("Music is too LOUD", "A música está muito ALTA", "संगीत बहुत तेज़ है" ).toMutableList())
        }


    }

    private fun saveMyPreferences() {

        val sharedPreference = getSharedPreferences("MyPreferences", MODE_PRIVATE)
        sharedPreference.edit {

            putString("oldVotes", listOfVotes.take(10).joinToString("###") { it })
        }

    }



    // UI
    @OptIn(
        ExperimentalMaterial3Api::class
    )
    fun myUI() {

        setContent {

            // Variables

            val animatedAlpha: Float by animateFloatAsState(if (beaconNearby) 0.45f else -0.45f, label = "alpha",animationSpec = tween(durationMillis = updateFrequency.toInt()))

            val tallyList = remember { mutableStateListOf<VoteToTallyFadeClass>() }

            var tallyColor by remember { mutableStateOf(Color.Black) }

            var textTyped by rememberSaveable { mutableStateOf("") }

            var expanded by remember { mutableStateOf(false) }

            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            val myContext = LocalContext.current

            tallyList.clear()
            runBlocking {
                // Log.d("###","@@@ getAll  setContent")
                voteDb.getAll().forEach {tallyList.add(it)}
            }

            fun updateMyVote(){

                runBlocking {voteChannel.send(IdVote("Me", myVote))}

                runBlocking {
                        voteDb.add()
                        tallyList.clear()
                    // Log.d("###","@@@ getAll  updateMyVote")
                        voteDb.getAll().forEach {tallyList.add(it)}
                        // Log.d("###","~~~~ updateMyVote triggering broadcastUpdate")
                        broadcastUpdate()
                }
            }

            AppTheme {

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)

                ){
                    Scaffold(
                        modifier = Modifier.padding(all = 18.dp),
                        topBar = {

                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = {
                                    expanded = !expanded

                                },
                                modifier = Modifier.padding(all = 18.dp)

                            ) {
                                TextField(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(PrimaryEditable, true)
                                        ,

                                    value = textTyped,

                                    placeholder = { Text(pendingLabel) },
                                    onValueChange = {
                                        if (it.length <= maxVoteLength) textTyped = it
                                        else Toast.makeText(
                                            myContext,
                                            "Cannot be more than $maxVoteLength characters",
                                            Toast.LENGTH_SHORT
                                        ).show()

                                    },
                                    singleLine = true,
                                    trailingIcon = {

                                        if (textTyped.isBlank()) {

                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)

                                        }
                                        else{

                                            IconButton(
                                                onClick = {
                                                    textTyped = ""
                                                    focusManager.clearFocus()
                                                    myVote = pendingLabel
                                                    updateMyVote()

                                                }

                                            ) {
                                                Icon(

                                                    painter = painterResource(id = R.drawable.ic_baseline_clear_24),
                                                    contentDescription = "Clear",
                                                    modifier = Modifier.size(30.dp),
                                                )
                                            }


                                        }


                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                                    keyboardActions = KeyboardActions(
                                        onDone = {

                                            // remove defaults

                                            if(firstVote){
                                                firstVote = false
                                                listOfVotes.clear()
                                            }

                                            // add entry if it's new
                                            if (!listOfVotes.contains(textTyped)) {
                                                listOfVotes.add(0,textTyped)
                                            }

                                            // Hide keyboard
                                            keyboardController?.hide()
                                            expanded = false
                                            focusManager.clearFocus()

                                            myVote = textTyped

                                            updateMyVote()

                                        }
                                    )
                                )

                                // filter options based on text field value

                                val filteringOptions = listOfVotes.filter { it.contains(textTyped, ignoreCase = true) }

                                if (filteringOptions.isNotEmpty()) {
                                    ExposedDropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false},


                                    ) {
                                        filteringOptions.forEach { selectionOption ->

                                            DropdownMenuItem(
                                                text = {Text(text = selectionOption)},

                                                onClick = {
                                                    textTyped = selectionOption

                                                    // Hide keyboard
                                                    keyboardController?.hide()
                                                    expanded = false
                                                    focusManager.clearFocus()

                                                    myVote = textTyped
                                                    updateMyVote()


                                                },

                                                )

                                        }
                                    }

                                }

                            }


                        },
                        bottomBar = {

                            val sortByVote = remember { mutableStateOf(true) }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(all = 16.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ){


                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(all = 0.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),

                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically

                                ){
                                    // Sort icon
                                    IconButton(onClick = {

                                        voteDb.sortByVote = !voteDb.sortByVote
                                        sortByVote.value = !sortByVote.value


                                    }) {

                                        Icon(
                                            painter = if (sortByVote.value) painterResource(id = R.drawable.ic_baseline_favorite_border_24) else painterResource(id = R.drawable.ic_baseline_sort_by_alpha_24),
                                            contentDescription = "Change",
                                            modifier = Modifier.size(30.dp)
                                        )


                                    }

                                    // Nearby icon

                                    Icon(
                                        painter = painterResource(id = R.drawable.outline_wifi_black_24) ,
                                        contentDescription = "Nearby",
                                        modifier = Modifier.size(32.dp).alpha(0.1f+(if(beaconNearby) abs(animatedAlpha-0.45f) else abs(animatedAlpha+0.45f) ))
                                    )




                                    // Reset options icon
                                    IconButton(onClick = {

                                        listOfVotes = listOf("Music is too LOUD", "A música está muito ALTA", "संगीत बहुत तेज़ है" ).toMutableList()
                                        firstVote = true

                                    }) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_baseline_settings_backup_restore_24),
                                            contentDescription = "Reset",
                                            modifier = Modifier.size(30.dp),
                                        )


                                    }


                                }



                            }


                        },
                        content = { paddingValues ->

                            LazyColumn(
                                contentPadding = paddingValues,
                                modifier = Modifier.background(MaterialTheme.colorScheme.background)
                            ) {
                                items(tallyList) { eachTally ->

                                    tallyColor = MaterialTheme.colorScheme.primaryContainer
                                    val ageFade = eachTally.ageFade

                                    tallyColor = Color(tallyColor.red,tallyColor.green, tallyColor.blue, ageFade,tallyColor.colorSpace)


                                    Card(
                                        colors= cardColors(containerColor = tallyColor,contentColor = tallyColor),
                                        onClick = {
                                            if (eachTally.vote!=pendingLabel) {

                                                myVote = eachTally.vote
                                                textTyped = myVote

                                                updateMyVote()
                                            }

                                        },

                                        modifier = Modifier
                                            .padding(horizontal = 16.dp, vertical = 4.dp)
                                            .fillMaxWidth()
                                            .alpha(ageFade)

                                    ) {

                                        Row(

                                            modifier = Modifier
                                                .padding(all = 8.dp)
                                                .background(color = tallyColor)

                                        ) {

                                            Text(
                                                text = eachTally.nVotes.toString(),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(all = 4.dp),
                                                maxLines = Int.MAX_VALUE,
                                                )

                                            Spacer(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .width(5.dp)
                                            )

                                            Text(
                                                text = eachTally.vote,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(all = 4.dp),
                                                maxLines = Int.MAX_VALUE,
                                            )
                                        }
                                    }


                                }

                            }
                        },
                    )
                }
            }

        }

    }

    // Activity Lifecycle

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Log.d("###"," onCreate")
        checkPermissions()
        getMyPreferences()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        connectionsClient = Nearby.getConnectionsClient(this)

        getLoc()


        // Recurring event to update screen every updateFrequency
        updateFrequencyTimer.schedule(

            object : TimerTask() {

                override fun run() {
                    if (!timerOn) {return}

                    runOnUiThread {

                        runBlocking {voteChannel.send(IdVote("Me", myVote))}

                        runBlocking {
                            voteDb.add()
                            // Log.d("###","~~~~~~~~~~~~~ Timer triggering broadcastUpdate")
                            broadcastUpdate()

                            // Log.d("###","  @@@@@@  beaconNearby just changed")

                            myUI()
                            beaconNearby=!beaconNearby
                            // Log.d("###","      @@@@  after myUI")
                        }
                    }
                }
            },
            0,
            updateFrequency
        )

    }

    @CallSuper
    override fun onResume() {
        super.onResume()

        // Log.d("###"," onResume")

        timerOn = true

        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()

        connectionsClient.startDiscovery(packageName, endpointDiscoveryCallback, options)


    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putString("myId", myId)
        savedInstanceState.putString("myVote", myVote)


    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        myId = savedInstanceState.getString("myId").toString()
        myVote = savedInstanceState.getString("myVote").toString()

    }

    @CallSuper
    override fun onPause() {

        // Log.d("###"," onPause stop")

        timerOn = false

        connectionsClient.stopAdvertising()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopDiscovery()

        super.onPause()

    }


    @CallSuper
    override fun onStop() {

        saveMyPreferences()

        timerOn = false

        // Log.d("###"," onStop")

        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()



        super.onStop()
    }

    @CallSuper
    override fun onDestroy() {

        // Log.d("###"," onDestroy")

        updateFrequencyTimer.cancel()

        super.onDestroy()
    }

}


