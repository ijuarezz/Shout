package com.shout_app

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
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
import com.shout_app.ui.theme.AppTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Timer
import java.util.TimerTask
import kotlin.Float.Companion.POSITIVE_INFINITY


// Log.d("###","")

// *******************  CONSTANTS   *******************
const val screenFreq: Long = 1 * 1000   //  1 sec
// const val screenFreq: Long = 5 * 1000   //  5 sec
const val locFreq: Long = 60 * 1000   //  1 min

const val tooOldDuration: Long = 2 * 60   //  2 mins
// const val tooOldDuration: Long = 10   //  10 SECS

const val maxVoteLength: Int = 40   // 40 chars

const val sep = "╚"
const val emptyVote = "<no vote>"

const val barUnicode = "\u275a"

class MainActivity : ComponentActivity() {


    // *******************  CLASSES   *******************
    data class IdVote(val id: String, val vote: String)

    // *******************  VARIABLES   *******************
    var maxDistance = 10f  //   10 meters
    var myLat: Double = 0.0
    var myLong: Double = 0.0
    private var myId: String = ""
    private var myVote=emptyVote
    private var playIntro: Boolean = true

    private var votesCount:Int=0
    private var noVotesCount:Int=0

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private val strategy = Strategy.P2P_CLUSTER
    private lateinit var connectionsClient: ConnectionsClient

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

    private lateinit var votesSorted: List<Pair<String, Int>>


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

                iterator.remove()

                val lostId = iVote.key
                val lostVote = idToVote[lostId].toString()
                val lostVoteCount = (votesSummary[lostVote]?:0)-1   // decrease counter for lostVote


                idToVote.remove(lostId)

                if(lostVote == emptyVote) {noVotesCount--}
                else{votesCount--}


                if(lostVoteCount>0) {   // update counter
                    votesSummary[lostVote] = lostVoteCount
                }
                else{  // or delete lostVote
                    votesSummary.remove(lostVote)
                }

            }
        }


        // update endpoints
        while(!endPointChannel.isEmpty){
            val it = endPointChannel.receive()

            if(it.substring(0,1)=="+") {
                endpointsList.add(it.substring(1,it.length))
                            }
            else{
                endpointsList.remove(it.substring(1,it.length))
            }


        }


        // process votes
        val voteChannelTime = System.currentTimeMillis() /1000

        while(!voteChannel.isEmpty){

            val it = voteChannel.receive()

            val thisVote = it.vote


            idToTime[it.id] = voteChannelTime


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


        // sort by votes
        votesSorted = votesSummary.toList().sortedBy { (_, v) -> v }.reversed().toList()



        if (!timerScreenOn) return

        // check counters before calling UI
        if ( (noVotesCount<1) && (myVote == emptyVote) )  noVotesCount=1

        if ( (votesCount<1) && (myVote != emptyVote) )  votesCount=1

        myUI()

        if (endpointsList.isEmpty()) return

        val p = "$myId$sep$myLat$sep$myLong$sep$myVote"
        connectionsClient.sendPayload(endpointsList,Payload.fromBytes(p.toByteArray()))


    }


    // *******************  NEARBY   *******************
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {


            connectionsClient.acceptConnection(endpointId, payloadCallback)


        }


        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {

        }
        override fun onDisconnected(endpointId: String) {

        }

    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {



            runBlocking {endPointChannel.send("+$endpointId")}

            runBlocking {      // to avoid collisions
                if(myId.toLong()<info.endpointName.toLong()) runBlocking{
                    delay(200L)
                }
            }

            runBlocking {

                connectionsClient.requestConnection(
                    myId,
                    endpointId,
                    connectionLifecycleCallback
                )
            }


        }
        override fun onEndpointLost(endpointId: String) {

            runBlocking {endPointChannel.send("-$endpointId")}
        }
    }

    private val payloadCallback: PayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {

            val p = String(payload.asBytes()!!, Charsets.UTF_8)
            val aInfo :  List<String> = p.split(sep)


            // Check if 4 fields were received
            if (aInfo.size != 4) return

            val newId: String = aInfo[0]
            val newLat: String = aInfo[1]
            val newLong: String = aInfo[2]
            var newVote: String = aInfo[3]

            val newLatD: Double = newLat.toDouble()
            val newLongD: Double = newLong.toDouble()

            // Warning if missing location
            if(myLat == 0.toDouble()){
            Toast.makeText(this@MainActivity, getString(R.string.location_error), Toast.LENGTH_LONG).show()
            myLat = newLatD
            myLong = newLongD
            }

            // Check for empty strings
            if(newId.isEmpty() or newVote.isEmpty()) return

            // Check if within max distance
            val newDistance: FloatArray = floatArrayOf(0f)
            Location.distanceBetween(newLatD,newLongD, myLat,myLong,newDistance)

            if (newDistance[0] > maxDistance) newVote=emptyVote

            // Add new vote
            runBlocking {voteChannel.send(IdVote(newId,newVote))}


        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {

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

                }

            }

    }



    private fun checkPermissions() {

        var votePermissions =
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )

        if (Build.VERSION.SDK_INT >= 31) {
            votePermissions+= arrayOf(
                Manifest.permission.NFC,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
            )
        }

        if (Build.VERSION.SDK_INT >= 33) {
            votePermissions+= arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
            )
        }


        val voteRequestPermission =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->


                // Check result
                var resultOK = true
                result.values.forEach { if (!it)  resultOK=false }

                if (resultOK) {
                    // *******************  FLOW    *******************
                    onBoarding()
                }
                else {

                    setContent {
                        AppTheme {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                            )
                            {

                                AlertDialog(
                                    onDismissRequest = { },
                                    title = { Text(getString(R.string.pMissingPermissions)) },
                                    text = { Text(getString(R.string.pPleaseOpen)) },
                                    confirmButton = {
                                        TextButton(onClick = {

                                            // Reset for next run & exit
                                            val packageName = applicationContext.packageName
                                            val runtime = Runtime.getRuntime()
                                            runtime.exec("pm clear $packageName")

                                            finish()

                                        }) {
                                            Text(getString(R.string.pExit))
                                        }
                                    }
                                )
                            }
                        }
                    }



                }
            }


        // Check existing permissions
        var needPermission = "None"
        votePermissions.forEach { if (checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED) needPermission = it  }
        if (needPermission == "None") {
            // *******************  FLOW    *******************
            onBoarding()
            return
        }

        setContent {
            AppTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                )
                {

                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text(getString(R.string.pPermissionsRequired)) },
                        text = { Text(getString(R.string.pBluetooth)) },
                        confirmButton = {
                            TextButton(onClick = {

                                voteRequestPermission.launch(votePermissions)

                            }) {
                                Text(getString(R.string.pContinue))
                            }
                        }
                    )
                }
            }
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

        // Check playIntro
        if (sharedPreference.contains("playIntro")) {
            playIntro = sharedPreference.getString("playIntro", "true").toBoolean()
        }
        else {
            editor.putString("playIntro", "true")
            editor.apply()
    }


    }

    private fun startNearby(){

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        connectionsClient = Nearby.getConnectionsClient(this)

        // start Discovery
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(packageName, endpointDiscoveryCallback, options)

        // start Advertising
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()

        runBlocking {
            async{
                connectionsClient.startAdvertising(
                    myId,
                    packageName,
                    connectionLifecycleCallback,
                    advertisingOptions
                )
            }
        }

        // *******************  FLOW    *******************
        startTimers()

    }

    private fun startTimers() {

        // Recurring event to update Screen & Broadcast
        timerProcess.schedule(
            object : TimerTask() {

                override fun run() {



                    runOnUiThread {
                        runBlocking { voteChannel.send(IdVote(myId, myVote)) }
                        runBlocking { process() }
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
                    if (!timerLocOn) return
                    runOnUiThread {
                        getLoc()
                    }
                }
            },
            0,
            locFreq
        )

    }






    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putString("myId", myId)
        savedInstanceState.putString("myVote", myVote)
        savedInstanceState.putFloat("maxDistance", maxDistance)


    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        myId = savedInstanceState.getString("myId").toString()
        myVote = savedInstanceState.getString("myVote").toString()
        maxDistance = savedInstanceState.getFloat("maxDistance")

    }

    // *******************  UI   *******************


    private fun onBoarding()  {

        if(playIntro){

            setContent {

                AppTheme {

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    )
                    {
                        val pagerState = rememberPagerState(initialPage = 0, pageCount = { 5 })
                        val coroutineScope = rememberCoroutineScope()

                        Scaffold(
                            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 40.dp),
                            topBar = {
                                Box(
                                    modifier = Modifier.fillMaxWidth()
                                ){
                                    Text(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .padding(all = 10.dp),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontSize = 16.sp,
                                        text =
                                            when (pagerState.currentPage) {
                                                0 -> getString(R.string.intro_basic)
                                                1 -> getString(R.string.intro_input)
                                                2 -> getString(R.string.intro_pizza)
                                                3 -> getString(R.string.intro_std_range)
                                                4 -> getString(R.string.intro_ext_range)
                                                else -> getString(R.string.intro_basic)
                                        }
                                    )
                                }

                                },
                            bottomBar = {},
                            content = { paddingValues ->

                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.background)
                                        .padding(all = paddingValues.calculateTopPadding())
                                        //.weight(1f)
                                ) { page ->

                                        Image(
                                           painter = painterResource(
                                                id =
                                                    when (page) {
                                                        0 -> R.drawable.__basic
                                                        1 -> R.drawable.__input
                                                        2 -> R.drawable.__pizza
                                                        3 -> R.drawable.__std_range
                                                        4 -> R.drawable.__ext_range
                                                        else -> R.drawable.__basic
                                                    }
                                            ),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                }

                            },
                            floatingActionButton = {

                                val infiniteTransition = rememberInfiniteTransition()
                                val animatedScale = infiniteTransition.animateFloat(
                                    initialValue = 0.8f,
                                    targetValue = 1.0f,
                                    animationSpec = InfiniteRepeatableSpec(
                                        animation = tween(durationMillis = 500),
                                        repeatMode = RepeatMode.Reverse
                                    )
                                )

                                FloatingActionButton(

                                    onClick = {

                                        if(pagerState.currentPage==4){

                                            val sharedPreference =
                                                getSharedPreferences("MyPreferences", MODE_PRIVATE)
                                            val editor = sharedPreference.edit()

                                            if (sharedPreference.contains("playIntro")) {
                                                editor.putString("playIntro", "false")
                                                editor.apply()

                                                // *******************  FLOW    *******************
                                                startNearby()

                                            }

                                        }
                                        else {
                                            coroutineScope.launch {
                                                pagerState.scrollToPage(pagerState.currentPage+1)
                                            }
                                        }
                                    },
                                    Modifier.scale(animatedScale.value)
                                ) {

                                    Icon(

                                        painter = painterResource(id =
                                            if(pagerState.currentPage==4){
                                                R.drawable.ic_baseline_clear_24
                                            }
                                            else{
                                                R.drawable.east_24px
                                            }
                                        ),
                                        contentDescription = "Change",
                                        modifier = Modifier.size(30.dp)
                                    )
                                }

                            },
                            floatingActionButtonPosition = FabPosition.Center
                        )
                    }

                }

            }
        }
        else {
            // *******************  FLOW    *******************
            startNearby()
        }
    }



    private fun myUI() {

        setContent {

            // Variables
            var tallyColor: Color

            val focusRequester = remember { FocusRequester() }

            var textTyped by rememberSaveable { mutableStateOf("") }
            var extendedMode by rememberSaveable { mutableStateOf(false) }


            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current
            val myContext = LocalContext.current


            // For Back Gesture to work as Back button
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
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 40.dp),
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
                                                .absolutePadding(
                                                    top = 9.dp,
                                                    bottom = 9.dp,
                                                    left = 10.dp
                                                )
                                                .defaultMinSize(minWidth = 16.dp)
                                                .align(Alignment.CenterVertically)
                                                .clickable { focusRequester.requestFocus() },
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
                                                    runBlocking {
                                                        voteChannel.send(
                                                            IdVote(
                                                                myId,
                                                                myVote
                                                            )
                                                        )
                                                    }
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
                                                    .absolutePadding(
                                                        top = 9.dp,
                                                        bottom = 9.dp,
                                                        left = 10.dp
                                                    )
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
                                                            runBlocking {
                                                                voteChannel.send(
                                                                    IdVote(
                                                                        myId,
                                                                        myVote
                                                                    )
                                                                )
                                                            }
                                                        }

                                                )
                                            }




                                        }
                                    }


                                }

                            }

                        },
                        floatingActionButton = {

                            FloatingActionButton(
                                onClick = {

                                    extendedMode = !extendedMode

                                    if(extendedMode){
                                        maxDistance=POSITIVE_INFINITY
                                        Toast.makeText(this@MainActivity, getString(R.string.range_ext), Toast.LENGTH_LONG).show()
                                    }
                                    else {
                                        maxDistance=10f
                                        Toast.makeText(this@MainActivity, getString(R.string.range_std), Toast.LENGTH_LONG).show()
                                    }


                                }
                            ) {

                                Icon(

                                    painter = if (extendedMode) painterResource(id = R.drawable.spatial_tracking_24px) else painterResource(id = R.drawable.spatial_audio_off_24px) ,
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

        getMyPreferences()
        checkPermissions()

    }

    @CallSuper
    override fun onResume() {
        super.onResume()



        timerScreenOn = true
        timerLocOn = true

    }

    @CallSuper
    override fun onPause() {



        timerScreenOn = false
        timerLocOn = false

        super.onPause()

    }

    @CallSuper
    override fun onStop() {

        super.onStop()
    }

    @CallSuper
    override fun onDestroy() {


        timerProcess.cancel()
        timerLoc.cancel()

        if (this::connectionsClient.isInitialized) {
            connectionsClient.stopAdvertising()
            connectionsClient.stopAllEndpoints()
            connectionsClient.stopDiscovery()
        }

        super.onDestroy()
    }

}
