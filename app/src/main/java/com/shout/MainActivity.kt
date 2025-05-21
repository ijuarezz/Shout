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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults.cardColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs


// CONSTANTS
const val updateFrequency: Long = 10 * 1000   //  10 secs
const val tooOldDuration: Long = 5 * 60   //  5 mins
// const val tooOldDuration: Long = 30   //  30 secs
const val maxDistance: Float = 10f  //   10 meters
const val maxVoteLength: Int = 30   // 30 chars
const val pendingLabel = "\u25E6\u25E6\u25E6" // black dot: u25CF  dotted: u25CC   bullet:25E6


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
                            timeStamp = System.currentTimeMillis()/1000
                        )
                    }
                }

                // Log.d("###","========   VoteDbClass  finished  ")

        }



        suspend fun getAll(): List<VoteToTallyFadeClass> {


            val votesSummary: MutableMap<String, Int> = HashMap()
            val votesTimestamp: MutableMap<String, Long> = HashMap()


            val votesOutput = mutableListOf<VoteToTallyFadeClass>()
            val tooOld: Long = (System.currentTimeMillis()/1000) - tooOldDuration

            // This is the only section that needs to block the DB
            mutexVote.withLock {

                //delete old entries by time first
                val iterator = idToVoteTime.iterator()
                while (iterator.hasNext()) {
                    val thisVote = iterator.next()
                    if (thisVote.value.timeStamp < tooOld) {
                        iterator.remove()
                    }
                }

                // tally votes
                for (thisVote in idToVoteTime) {
                    val v = thisVote.value.vote
                    votesSummary[v] = (votesSummary[v]?:0) + 1  // change null to 0
                    votesTimestamp[v] =  (votesTimestamp[v] ?:0)  + (thisVote.value.timeStamp - tooOld)   // accumulating ages in seconds
                }
            }


            // sort by either votes or alphabetically
            val votesSorted: Map<String, Int> =

            if (sortByVote) votesSummary.toList().sortedBy { (_, v) -> v }.reversed().toMap()
            else votesSummary.toList().sortedBy { (k, _) -> k }.toMap()

            // fade based on time
            var ageFade: Float

            for (thisVote in votesSorted) {

                val avgTimestamp = (votesTimestamp[thisVote.key] ?: 0L)/ thisVote.value  //  average age = (sum of ages) / (sum of votes)

                ageFade = avgTimestamp.toFloat() / tooOldDuration.toFloat()  // how old the vote is expressed as 0-100%
                ageFade = (ageFade * 0.8f)+0.2f  // controls transparency but in the 20-100% range

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

    val tallyList =  mutableStateListOf<VoteToTallyFadeClass>()

    private var updateFrequency10 = 0

    private var myId: String = ""

    var myLat: Double = 0.0
    var myLong: Double = 0.0
    var myVote: String = pendingLabel

    private var beaconVote: String="0"
    private var beaconNearby: Boolean=false

    val voteChannel = Channel<IdVote>(Channel.UNLIMITED)
    var voteDb = VoteDbClass(voteChannel)

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

        override fun onEndpointLost(endpointId: String) {} //Log.d("###","Lost  $endpointId")

    }
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {} //Log.d("###","Incoming from ${info.endpointName}")
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {} //Log.d("###","Result  $endpointId")
        override fun onDisconnected(endpointId: String) {} // Log.d("###","Disconnected  $endpointId")

    }

    private var fusedLocationClient: FusedLocationProviderClient? = null

    //Nearby functions

    @SuppressLint("MissingPermission")
    fun broadcastUpdate() {

        // Log.d("###","======== broadcastUpdate")

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



    }




    fun updateMyVote(){

        runBlocking {voteChannel.send(IdVote("Me", myVote))}

        runBlocking {
            voteDb.add()
            tallyList.clear()
            voteDb.getAll().forEach {tallyList.add(it)}
            broadcastUpdate()
        }
    }





    // UI
    @OptIn(
        ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class
    )
    fun myUI() {

        setContent {

            // Variables
            // var tallyColor by remember { mutableStateOf(Color.Black) }
            var tallyColor = Color.Black

            val focusRequester = remember { FocusRequester() }

            var textTyped by rememberSaveable { mutableStateOf("") }

            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            val myContext = LocalContext.current


            AppTheme {

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)

                ){


                    Scaffold(
                        modifier = Modifier.padding(all = 18.dp),
                        topBar = {
                        /*

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

                                /*

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

                                 */

                            }

                         */

                            tallyColor = if(myVote == pendingLabel){
                                MaterialTheme.colorScheme.primaryContainer
                            } else{
                                MaterialTheme.colorScheme.surfaceVariant
                            }

                            Card(

                                colors= cardColors(containerColor = tallyColor,contentColor = tallyColor),

                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                                    .wrapContentHeight()
                            ) {

                                Row(

                                    modifier = Modifier
                                        //.padding(all = 8.dp)
                                        .background(color = tallyColor),
                                        verticalAlignment =  Alignment.CenterVertically


                                ) {


                                    Text(
                                        modifier = Modifier.clickable { focusRequester.requestFocus()},
                                        text = "   +",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = Int.MAX_VALUE,
                                        fontSize = 20.sp,


                                    )


                                    TextField(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(focusRequester),

                                        value = textTyped,
                                        placeholder = { Text("Select from below or add new") },
                                        singleLine = true,

                                        colors=TextFieldDefaults.colors(
                                            focusedContainerColor = tallyColor,
                                            unfocusedContainerColor = tallyColor,

                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent,
                                            disabledIndicatorColor = Color.Transparent
                                        ),


                                        onValueChange = {
                                            if (it.length <= maxVoteLength) textTyped = it
                                            else Toast.makeText(
                                                myContext,
                                                "Cannot be more than $maxVoteLength characters",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },

                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                myVote = textTyped
                                                textTyped=""
                                                updateMyVote()
                                            }
                                        ),


                                    )

                                }
                            }

                        },
                        bottomBar = {
                            Text(
                                text =  (if (beaconNearby) "\u25CC\u25CF" else "\u25CF\u25CC"),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleLarge,
                            )

                        },
                        content = { paddingValues ->

                            LazyColumn(
                                contentPadding = paddingValues,
                                modifier = Modifier.background(MaterialTheme.colorScheme.background)
                            ) {
                                items(tallyList) { eachTally ->

                                    tallyColor = if(myVote == eachTally.vote){
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else{
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }

                                    tallyColor = Color(tallyColor.red,tallyColor.green, tallyColor.blue, eachTally.ageFade,tallyColor.colorSpace)

                                    Card(
                                        colors= cardColors(containerColor = tallyColor,contentColor = tallyColor),
                                        onClick = {
                                            if (eachTally.vote!=pendingLabel) {

                                                myVote = eachTally.vote
                                                updateMyVote()
                                            }

                                        },

                                        modifier = Modifier
                                            .padding(horizontal = 16.dp, vertical = 4.dp)
                                            .fillMaxWidth()
                                            .alpha(eachTally.ageFade)

                                    ) {

                                        Row(

                                            modifier = Modifier
                                                .padding(all = 8.dp)
                                                .background(color = tallyColor)

                                        ) {

                                            Text(
                                                text = eachTally.nVotes.toString(),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(all = 9.dp),
                                                maxLines = Int.MAX_VALUE,
                                                )

                                            /*
                                            Spacer(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .width(8.dp)
                                            )
                                            */

                                            Text(
                                                text = eachTally.vote,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(all = 9.dp),
                                                maxLines = Int.MAX_VALUE,
                                            )
                                        }
                                    }


                                }

                            }

                        },
                        floatingActionButton = {
                            //todo replace Icons with single Icon ?
                            /*

                            val sortByVote = remember { mutableStateOf(true) }
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

                             */

                            FloatingActionButton(
                                onClick = { /* do something */ },
                            ) {
                                Icon(Icons.Filled.Add, "Localized description")
                            }

                        },
                        floatingActionButtonPosition = FabPosition.End
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
                       updateMyVote()

                        runBlocking {
                            myUI()
                            beaconNearby=!beaconNearby
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



/*

                            LinearProgressIndicator(
                                progress = {
                                    if(beaconNearby) abs(progressBar-0.5f) else abs(progressBar+0.5f)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                // color = COMPILED_CODE,
                                // trackColor = COMPILED_CODE,
                                // strokeCap = COMPILED_CODE,
                            ){Log.d("###"," progress ${if(beaconNearby) abs(progressBar-0.5f) else abs(progressBar+0.5f)}")}







                            // Nearby icon
                            Icon(
                                painter = painterResource(id = R.drawable.outline_wifi_black_24) ,
                                contentDescription = "Nearby",
                                modifier = Modifier
                                    .size(32.dp)
                                    .alpha(0.1f+(if(beaconNearby) abs(animatedAlpha-0.45f) else abs(animatedAlpha+0.45f) ))
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick={}
                                    )
                            )







                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(all = 16.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)

                            ){


                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(all = 0.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),

                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically

                                ){
                                }
                            }


 */
