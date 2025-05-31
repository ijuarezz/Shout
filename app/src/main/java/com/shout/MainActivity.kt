package com.shout

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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults.cardColors
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
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
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.OnTokenCanceledListener
import com.shout.ui.theme.AppTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.util.Timer
import java.util.TimerTask


// *******************  CONSTANTS   *******************
// const val screenFreq: Long = 5 * 1000   //  5 sec
const val screenFreq: Long = 1 * 1000   //  1 sec
const val locFreq: Long = 60 * 1000   //  1 min

const val tooOldDuration: Long = 3 * 60   //  3 mins
const val maxDistance: Float = 10f  //   10 meters
const val maxVoteLength: Int = 30   // 30 chars

const val sep = "╚"

class MainActivity : ComponentActivity() {


    // *******************  CLASSES   *******************
    data class IdVote(val id: String, val vote: String)

    // *******************  VARIABLES   *******************
    var myLat: Double = 0.0
    var myLong: Double = 0.0
    private var myId: String = ""
    private var myVote=""

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private val strategy = Strategy.P2P_CLUSTER
    private lateinit var connectionsClient: ConnectionsClient


    // *******************  BOOLEANS   *******************
    private var sortByVote: Boolean = true

    // todo
    // mutex for Loc ?

    // *******************  CHANNELS   *******************
    val voteChannel = Channel<IdVote>(Channel.UNLIMITED)
    val pointChannel = Channel<String>(Channel.UNLIMITED)


    // *******************  TIMERS   *******************
    private val timerScreen = Timer(true)
    var timerScreenOn = true

    private val timerLoc = Timer(true)
    var timerLocOn = true

    // *******************  TABLES   *******************
    private var idToVote = mutableMapOf<String, String>()
    private var idToTime = mutableMapOf<String, Long>()
    private val votesSummary: MutableMap<String, Int> = HashMap()
    private val pointsList = mutableListOf<String>()


    // *******************  FUNCTIONS   *******************

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun process(){

        var lastVote:String
        var lastVoteCount: Int
        val tooOld: Long = (System.currentTimeMillis()/1000) - tooOldDuration

        broadcastUpdate()

        //delete old entries by time first
        val iterator = idToTime.iterator()
        while (iterator.hasNext()) {
            val thisVote = iterator.next()

            if (thisVote.value < tooOld) {  // remove vote & timestamp
                idToVote.remove(thisVote.key)
                iterator.remove()
            }
        }


        // update endpoints
        while(!pointChannel.isEmpty){
            val it = pointChannel.receive()

            if(it.substring(0,1)=="+") {
                pointsList.add(it.substring(1,it.length))
                // log("###", "======== endpoints  adding $it ")
                            }
            else{
                pointsList.remove(it.substring(1,it.length))
                // log("###", "======== endpoints  removing $it ")
            }

            // log("###", "======== endpoints  list is $pointsList ")

        }

        // todo
        // may be running for more than 1 sec
        while(!voteChannel.isEmpty){

            val it = voteChannel.receive()


            idToTime[it.id] = System.currentTimeMillis()/1000

            // log("###", "======== addVotes  processing ${it.id} ${it.vote} ")
            // log("###", "======== addVotes  idToVote  $idToVote")

            if(idToVote.containsKey(it.id)) { // voter exists

                lastVote = idToVote[it.id].toString()
                idToVote[it.id] = it.vote

                if (it.vote != lastVote) { // vote changed

                    votesSummary[it.vote] = (votesSummary[it.vote]?:0) + 1  // change null to 0
                    // log("###", "     vote added   ${it.vote}")


                    lastVoteCount = (votesSummary[lastVote]?:0)-1   // decrease counter for lastVote
                    // log("###", "decrease counter for   lastVote $lastVote    it.vote  ${it.vote}  lastVoteCount $lastVoteCount ")

                    if(lastVoteCount>0) {   // update counter
                        votesSummary[lastVote] = lastVoteCount
                    }
                    else{  // or delete vote

                        // log("###", "   vote deleted   count =0")
                        idToVote.remove(lastVote)
                        idToTime.remove(lastVote)
                        votesSummary.remove(lastVote)

                    }
                }
            }

            else{  // new voter

                // log("###", "======== addVotes  adding new ${it.id}  ${it.vote}")
                idToVote[it.id] = it.vote
                votesSummary[it.vote] = (votesSummary[it.vote]?:0) + 1  // change null to 0
            }


        }

        myUI()

        // // log("###", "SUMMARY")
        // // log("###", "          idToVote is $idToVote")
        // // log("###", "           idToTime is $idToTime")
        // // log("###", "            votesSummary is $votesSummary")
        // log("###", "-----------------------------------------------------------------------")
    }


    // *******************  NEARBY   *******************
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {

            // log("###","onConnectionInitiated from $endpointId")
            connectionsClient.acceptConnection(endpointId, payloadCallback)


        }


        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            // log("###","onConnectionResult   Result  $endpointId")
        }
        override fun onDisconnected(endpointId: String) {
            // log("###","Disconnected  $endpointId")
        }

    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {

            // log("###","onEndpointFound from $endpointId")
            runBlocking {pointChannel.send("+$endpointId")}

            if(myId.toLong()>info.endpointName.toLong()){

                // log("###","onEndpointFound requesting connection")
                connectionsClient.requestConnection(myId, endpointId, connectionLifecycleCallback)

            }



        }
        override fun onEndpointLost(endpointId: String) {
            // log("###","onEndpointLost  $endpointId")
            runBlocking {pointChannel.send("-$endpointId")}
        }
    }

    private val payloadCallback: PayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {

            val p = String(payload.asBytes()!!, Charsets.UTF_8)
            val aInfo :  List<String> = p.split(sep)

            // // log("###","payloadCallback  p $p    aInfo $aInfo ")

            // Check if 4 fields were received
            if (aInfo.size != 4) {return}

            val newId: String = aInfo[0]
            val newLat: String = aInfo[1]
            val newLong: String = aInfo[2]
            val newVote: String = aInfo[3]

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


            // Check for empty strings
            if(newId.isEmpty() or newVote.isEmpty()) return

            // Add new vote
            runBlocking {voteChannel.send(IdVote(newId,newVote))}


        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // // log("###","onPayload  TransferUpdate  endpointId: $endpointId")
            /*
            if (update.status == PayloadTransferUpdate.Status.SUCCESS
                && myChoice != null && opponentChoice != null) {
                val mc = myChoice!!
                val oc = opponentChoice!!
                when {
                    mc.beats(oc) -> { // Win!
                        binding.status.text = "${mc.name} beats ${oc.name}"
                        myScore++
                    }
                    mc == oc -> { // Tie
                        binding.status.text = "You both chose ${mc.name}"
                    }
                    else -> { // Loss
                        binding.status.text = "${mc.name} loses to ${oc.name}"
                        opponentScore++
                    }
                }
                binding.score.text = "$myScore : $opponentScore"
                myChoice = null
                opponentChoice = null
                setGameControllerEnabled(true)
            }


             */
        }
    }



    // *******************  COMMS   *******************

    @SuppressLint("MissingPermission")
    fun broadcastUpdate() {

        if ((myVote=="") || pointsList.isEmpty()) {return}

        val p = "$myId$sep$myLat$sep$myLong$sep$myVote"
        val bytesPayload = Payload.fromBytes(p.toByteArray())

        if (pointsList.isEmpty()) {return}

        // log("###","broadcastUpdate  sending to  $pointsList")

        connectionsClient.sendPayload(
            pointsList,
            bytesPayload
        )
    }

    private fun stopAll(){

        timerScreen.cancel()
        timerLoc.cancel()

        connectionsClient.stopAdvertising()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopDiscovery()


    }


    // *******************  FUNCTIONS   *******************
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
                    // // log("###","== Location: $myLat $myLong")
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

    // *******************  UI   *******************

    private fun myUI() {

        setContent {

            // Variables
            var tallyColor: Color

            val focusRequester = remember { FocusRequester() }
            val mySortByVote = rememberSaveable { mutableStateOf(true) }


            var textTyped by rememberSaveable { mutableStateOf("") }

            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            val myContext = LocalContext.current


            // sort by either votes or alphabetically
            val votesSorted =
                if (sortByVote) votesSummary.toList().sortedBy { (_, v) -> v }.reversed().toList()
                else votesSummary.toList().sortedBy { (k, _) -> k }.toList()

            // // log("###","myUI    votesSorted   $votesSorted")

            AppTheme {

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)

                ){


                    Scaffold(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 30.dp),
                        topBar = {

                            tallyColor = if(myVote == ""){
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
                                            .focusRequester(focusRequester)
                                            .onKeyEvent {
                                                if (it.key == Key.Back) {
                                                    keyboardController?.hide()
                                                    focusManager.clearFocus()
                                                    true
                                                } else {
                                                    false
                                                }
                                            },

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
                                            else Toast.makeText(myContext, "Max numbers of characters is $maxVoteLength",Toast.LENGTH_SHORT).show()
                                        },

                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                myVote = textTyped.trim()

                                                runBlocking {voteChannel.send(IdVote("Me",myVote))}
                                                textTyped=""
                                            }
                                        ),



                                    )

                                }
                            }

                        },
                        bottomBar = {

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.Absolute.Center

                            ){
                            }

                        },
                        content = { paddingValues ->

                            LazyColumn(
                                contentPadding = paddingValues,
                                modifier = Modifier.background(MaterialTheme.colorScheme.background)
                            ) {

                                 items(votesSorted.toList()) { eachTally ->
                                    tallyColor = if(myVote == eachTally.first){
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else{
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }

                                    Card(
                                        colors= cardColors(containerColor = tallyColor,contentColor = tallyColor),
                                        onClick = {
                                                myVote = eachTally.first
                                                runBlocking {voteChannel.send(IdVote("Me",myVote))}
                                        },

                                        modifier = Modifier
                                            .padding(horizontal = 16.dp, vertical = 4.dp)
                                            .fillMaxWidth()

                                    ) {

                                        Row(

                                            modifier = Modifier
                                                .padding(all = 8.dp)
                                                .background(color = tallyColor)

                                        ) {

                                            Text(
                                                text = eachTally.second.toString(),
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
                                                text = eachTally.first,
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
                            FloatingActionButton(
                                onClick = {
                                    sortByVote = !sortByVote
                                    mySortByVote.value = !mySortByVote.value
                                    }
                            ) {

                                Icon(

                                    painter = if (mySortByVote.value) painterResource(id = R.drawable.ic_baseline_favorite_border_24) else painterResource(id = R.drawable.ic_baseline_sort_by_alpha_24),
                                    contentDescription = "Change",
                                    modifier = Modifier.size(30.dp)
                                )
                            }

                        },
                        floatingActionButtonPosition = FabPosition.End
                    )
                }

            }

        }

    }

    // *******************  LIFECYCLE   *******************

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // log("###"," onCreate")
        checkPermissions()
        getMyPreferences()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        connectionsClient = Nearby.getConnectionsClient(this)

        // start Discovery
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(packageName, endpointDiscoveryCallback, options)

        // start Advertising
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(
            myId,
            packageName,
            connectionLifecycleCallback,
            advertisingOptions
        )


        // Recurring event to update Screen & Broadcast
        timerScreen.schedule(
            object : TimerTask() {

                override fun run() {
                    if (!timerScreenOn) {return}
                    runOnUiThread {
                        runBlocking {process()}
                    }
                }
            },
            0,
            screenFreq
        )

        // Recurring event to update Location
        timerLoc.schedule(

            object : TimerTask() {
                override fun run() {
                    if (!timerLocOn) {return}
                    runOnUiThread {
                        getLoc()}
                }
            },
            0,
            locFreq
        )

    }

    @CallSuper
    override fun onResume() {
        super.onResume()

        // log("###"," onResume")

        timerScreenOn = true
        timerLocOn = true

    }

    @CallSuper
    override fun onPause() {

        // log("###"," onPause")

        timerScreenOn = false
        timerLocOn = false

        super.onPause()

    }

    @CallSuper
    override fun onStop() {

        // log("###"," onStop")
        stopAll()

        super.onStop()
    }

    @CallSuper
    override fun onDestroy() {

        // log("###"," onDestroy")
        stopAll()

        super.onDestroy()
    }

}
