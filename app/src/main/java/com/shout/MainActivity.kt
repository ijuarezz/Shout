package com.shout

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.CallSuper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardDefaults.cardColors
import androidx.compose.material3.CardDefaults.outlinedCardBorder
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Timer
import java.util.TimerTask


// *******************  CONSTANTS   *******************
const val screenFreq: Long = 1 * 1000   //  1 sec
// const val screenFreq: Long = 5 * 1000   //  5 sec
const val locFreq: Long = 60 * 1000   //  1 min

const val tooOldDuration: Long = 2 * 60   //  2 mins
//const val tooOldDuration: Long = 10   //  10 SECS
const val maxDistance: Float = 10f  //   10 meters
const val maxVoteLength: Int = 30   // 30 chars

const val sep = "╚"
const val emptyVote = "<no vote>"

const val barUnicode = "\u275a"

class MainActivity : ComponentActivity() {


    // *******************  CLASSES   *******************
    data class IdVote(val id: String, val vote: String)

    // *******************  VARIABLES   *******************
    var myLat: Double = 0.0
    var myLong: Double = 0.0
    private var myId: String = ""
    private var myVote=emptyVote

    private var votesCount:Int=0
    private var noVotesCount:Int=0

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private val strategy = Strategy.P2P_CLUSTER
    private lateinit var connectionsClient: ConnectionsClient


    // *******************  BOOLEANS   *******************
    private var sortByVote: Boolean = true

    // todo
    // mutex for Loc ?

    // *******************  CHANNELS   *******************
    val voteChannel = Channel<IdVote>(Channel.UNLIMITED)
    val endPointChannel = Channel<String>(Channel.UNLIMITED)


    // *******************  TIMERS   *******************
    private val timerProcess = Timer(true)
    private var timerScreenOn = true

    private val timerLoc = Timer(true)
    var timerLocOn = true

    // *******************  TABLES   *******************
    private var idToVote = mutableMapOf<String, String>()
    private var idToTime = mutableMapOf<String, Long>()
    private val votesSummary: MutableMap<String, Int> = HashMap()
    private val endpointsList = mutableListOf<String>()


    // *******************  FUNCTIONS   *******************

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun process(){

        var lastVote:String
        var lastVoteCount: Int
        val tooOld: Long = (System.currentTimeMillis()/1000) - tooOldDuration

        //delete old entries by time first
        val iterator = idToTime.iterator()
        while (iterator.hasNext()) {
            val iVote = iterator.next()


            if (iVote.value < tooOld) {  // remove vote & timestamp

                // Log.d("###", "========     $iVote is too old")
                iterator.remove()

                val lostId = iVote.key
                val lostVote = idToVote[lostId].toString()
                val lostVoteCount = (votesSummary[lostVote]?:0)-1   // decrease counter for lostVote


                // Log.d("###", "========     before $idToVote")
                // Log.d("###", "========     before $votesSummary")
                idToVote.remove(lostId)
                votesCount--


                if(lostVoteCount>0) {   // update counter
                    votesSummary[lostVote] = lostVoteCount
                }
                else{  // or delete lostVote

                    votesSummary.remove(lostVote)

                }

                // Log.d("###", "========        after $idToVote")
                // Log.d("###", "========        after $votesSummary")

            }
        }

        // Log.d("###", ".........     idToVote $idToVote")
        // Log.d("###", ".........     votesSummary $votesSummary")


        // update endpoints
        while(!endPointChannel.isEmpty){
            val it = endPointChannel.receive()

            if(it.substring(0,1)=="+") {
                endpointsList.add(it.substring(1,it.length))
                // Log.d("###", "======== endpoints  adding $it ")
                            }
            else{
                endpointsList.remove(it.substring(1,it.length))
                // Log.d("###", "======== endpoints  removing $it ")
            }

            // Log.d("###", "======== endpoints  list is ${endpointsList}")

        }


        // process votes
        while(!voteChannel.isEmpty){

            val it = voteChannel.receive()

            val thisVote = it.vote


            idToTime[it.id] = System.currentTimeMillis()/1000


            if(idToVote.containsKey(it.id)) { // voter exists


                lastVote = idToVote[it.id].toString()

                if (thisVote != lastVote) { // vote changed


                    if(thisVote== emptyVote) {
                        noVotesCount++
                        votesCount--
                    }
                    else{
                        votesSummary[thisVote] = (votesSummary[thisVote]?:0) + 1
                    }

                    if(lastVote== emptyVote) {
                        noVotesCount--
                        votesCount++
                    }


                    lastVoteCount = (votesSummary[lastVote]?:0)-1   // decrease counter for lastVote

                    if(lastVoteCount>0) {   // update counter
                        votesSummary[lastVote] = lastVoteCount
                    }
                    else{  // or delete vote

                        idToVote.remove(lastVote)
                        idToTime.remove(lastVote)
                        votesSummary.remove(lastVote)

                    }
                }
            }

            else{  // new voter

                if(thisVote== emptyVote) {
                    noVotesCount++
                }
                else{
                    votesCount++
                    votesSummary[thisVote] = (votesSummary[thisVote]?:0) + 1
                }

            }

            idToVote[it.id] = thisVote
        }


        if (!timerScreenOn) {return}

        // check counters before calling UI
        if (noVotesCount<0) noVotesCount=0
        if (votesCount<0) votesCount=0

        myUI()

        if (endpointsList.isEmpty()) {return}

        // Log.d("###","sending  $myVote to $endpointsList")

        val p = "$myId$sep$myLat$sep$myLong$sep$myVote"
        connectionsClient.sendPayload(endpointsList,Payload.fromBytes(p.toByteArray()))

        // Log.d("###","broadcastUpdate  sending to  $endpointsList")


    }


    // *******************  NEARBY   *******************
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {

            // Log.d("###","onConnectionInitiated from $endpointId")
            connectionsClient.acceptConnection(endpointId, payloadCallback)


        }


        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            // Log.d("###","onConnectionResult   Result  $endpointId")
        }
        override fun onDisconnected(endpointId: String) {
            // Log.d("###","Disconnected  $endpointId")
        }

    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {

            runBlocking {endPointChannel.send("+$endpointId")}

            lifecycleScope.launch {

                // to avoid collisions
                if(myId.toLong()<info.endpointName.toLong()) runBlocking{
                    delay(200L)
                }
                connectionsClient.requestConnection(
                    myId,
                    endpointId,
                    connectionLifecycleCallback
                )
            }


        }
        override fun onEndpointLost(endpointId: String) {
            // Log.d("###","onEndpointLost  $endpointId")
            runBlocking {endPointChannel.send("-$endpointId")}
        }
    }

    private val payloadCallback: PayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {

            val p = String(payload.asBytes()!!, Charsets.UTF_8)
            val aInfo :  List<String> = p.split(sep)

            // Log.d("###","payloadCallback  p $p    aInfo $aInfo ")

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
            Toast.makeText(this@MainActivity, getString(R.string.location_error), Toast.LENGTH_LONG).show()
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
            // Log.d("###","payloadCallback      ADDING TO CHANNEL p $p    aInfo $aInfo ")
            runBlocking {voteChannel.send(IdVote(newId,newVote))}


        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Log.d("###","onPayload  TransferUpdate  endpointId: $endpointId")

        }
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

        // ce ACCESS_FINE_LOCATION, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN and READ_EXTERNAL_STORAGE


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

                        Toast.makeText(this, getString(R.string.permission_error), Toast.LENGTH_LONG).show()

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
            myId = sharedPreference.getString("myId", emptyVote).toString()
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

            // Todo  Is uiSortByVote really required ?
            val uiSortByVote = rememberSaveable { mutableStateOf(true) }


            var textTyped by rememberSaveable { mutableStateOf("") }

            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            val myContext = LocalContext.current


            // sort by either votes or alphabetically
            val votesSorted =
                if (sortByVote) votesSummary.toList().sortedBy { (_, v) -> v }.reversed().toList()
                else votesSummary.toList().sortedBy { (k, _) -> k }.toList()

            // For Back Gesture to works as Back button
            BackHandler(true) { finish() }

            /*
            val scope = rememberCoroutineScope()
            val isBackPressed = remember { mutableStateOf(false) }
            BackHandler(!isBackPressed.value) {
                isBackPressed.value = true
                Toast.makeText(myContext, "Press back again to exit", Toast.LENGTH_SHORT).show()
                scope.launch {
                    delay(2000L)
                    isBackPressed.value = false
                }
            }

            */



            AppTheme {

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)

                ){
                    Scaffold(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 40.dp),
                        topBar = {


                            tallyColor = if(myVote == emptyVote){
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
                                            .padding(horizontal = 4.dp)
                                            .background(color = tallyColor),
                                    ) {

                                        Text(
                                            modifier = Modifier
                                                .absolutePadding(top = 9.dp, bottom = 9.dp, left=10.dp)
                                                .defaultMinSize(minWidth = 16.dp)
                                                .align(Alignment.CenterVertically)
                                                .clickable { focusRequester.requestFocus()},
                                            text = "+",
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
                                            placeholder = { Text(getString(R.string.placeholder)) },
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
                                                else Toast.makeText(myContext, getString(R.string.max_chars_error)+" $maxVoteLength",Toast.LENGTH_SHORT).show()
                                            },

                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                                            keyboardActions = KeyboardActions(
                                                onDone = {
                                                    keyboardController?.hide()
                                                    focusManager.clearFocus()
                                                    myVote = textTyped.trim()
                                                    textTyped=""
                                                    runBlocking {voteChannel.send(IdVote(myId,myVote))}

                                                }
                                            ),



                                            )
                                    }
                                }





                        },




                        bottomBar = {

                            Card(

                                colors= cardColors(containerColor = MaterialTheme.colorScheme.background,contentColor = MaterialTheme.colorScheme.background),
                                border= outlinedCardBorder(true),
                                elevation = CardDefaults.elevatedCardElevation(),

                                // elevation = cardElevation(defaultElevation = 10.dp),

                                onClick = {Toast.makeText(myContext,
                                    "\u2611 $votesCount    \u2610 $noVotesCount",
                                    Toast.LENGTH_SHORT).show()},

                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                                    .wrapContentHeight()

                            ) {

                                Text(

                                    text = buildAnnotatedString {
                                        withStyle(style = SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primaryContainer)) {
                                            append(barUnicode.repeat(votesCount))
                                        }
                                        withStyle(style = SpanStyle(fontSize = 22.sp, color = MaterialTheme.colorScheme.surfaceVariant)) {
                                            append(barUnicode.repeat(noVotesCount))
                                        }
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }

                        },
                        content = { paddingValues ->
                            LazyColumn(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(top = paddingValues.calculateTopPadding())

                            ) {

                                 items(votesSorted.toList()) { eachTally ->

                                    val thisVote= eachTally.first
                                    val sameVote = (myVote==thisVote)

                                    tallyColor = if(sameVote){
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else{
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }



                                    Card(
                                        colors= cardColors(containerColor = tallyColor,contentColor = tallyColor),
                                        modifier = Modifier
                                            .padding(horizontal = 16.dp, vertical = 4.dp)
                                            .fillMaxWidth()
                                            .clickable {
                                                    if (!sameVote) {
                                                        myVote = thisVote
                                                        runBlocking {voteChannel.send(IdVote(myId,myVote))}
                                                    }

                                            }

                                    ) {

                                        Row(

                                            modifier = Modifier
                                                .padding(horizontal = 4.dp, vertical = 4.dp)
                                                .background(color = tallyColor)

                                        ) {

                                            Text(

                                                text = eachTally.second.toString(),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                maxLines = Int.MAX_VALUE,
                                                modifier = Modifier
                                                    .absolutePadding(top = 9.dp, bottom = 9.dp, left=10.dp)
                                                    .defaultMinSize(minWidth = 16.dp),

                                                )

                                            Spacer(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .width(14.dp)
                                            )


                                            Text(
                                                text = thisVote,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier
                                                    .padding(vertical = 9.dp)
                                                    .weight(1f),
                                                maxLines = Int.MAX_VALUE,

                                            )

                                            if (sameVote){

                                                Spacer(
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .width(4.dp)
                                                )



                                                Text(

                                                    text = "\u2715",
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    maxLines = Int.MAX_VALUE,
                                                    modifier = Modifier
                                                        .padding(all = 9.dp)
                                                        .clickable {
                                                            myVote = emptyVote
                                                            runBlocking {voteChannel.send(IdVote(myId,myVote))}
                                                        }

                                                )
                                            }




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
                                    uiSortByVote.value = !uiSortByVote.value
                                    }
                            ) {

                                Icon(

                                    painter = if (uiSortByVote.value) painterResource(id = R.drawable.format_list_numbered_24px) else painterResource(id = R.drawable.ic_baseline_sort_by_alpha_24),
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

        // Log.d("###"," onCreate")
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
        timerProcess.schedule(
            object : TimerTask() {

                override fun run() {

                    // Log.d("###"," TimerTask")

                    runOnUiThread {
                        runBlocking {voteChannel.send(IdVote(myId,myVote))}
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

        // Log.d("###"," onResume")

        timerScreenOn = true
        timerLocOn = true

    }

    @CallSuper
    override fun onPause() {

        // Log.d("###"," onPause")

        timerScreenOn = false
        timerLocOn = false

        super.onPause()

    }

    @CallSuper
    override fun onStop() {

        // Log.d("###"," onStop")
        super.onStop()
    }

    @CallSuper
    override fun onDestroy() {

        // Log.d("###"," onDestroy")

        timerProcess.cancel()
        timerLoc.cancel()

        connectionsClient.stopAdvertising()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopDiscovery()

        super.onDestroy()
    }

}
