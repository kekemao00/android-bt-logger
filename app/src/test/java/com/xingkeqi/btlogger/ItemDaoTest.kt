package com.xingkeqi.btlogger

import android.content.Context
import androidx.room.Room
import com.xingkeqi.btlogger.data.ConnectionRecordDao
import com.xingkeqi.btlogger.data.DeviceDao
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ItemDaoTest {

    private lateinit var itemDao: DeviceDao
    private lateinit var inventoryDatabase: ConnectionRecordDao
//
//    @Before
//    fun createDb() {
//        val context: Context = ApplicationProvider.getApplicationContext()
//        // Using an in-memory database because the information stored here disappears when the
//        // process is killed.
//        inventoryDatabase = Room.inMemoryDatabaseBuilder(context, InventoryDatabase::class.java)
//            // Allowing main thread queries, just for testing.
//            .allowMainThreadQueries()
//            .build()
//        itemDao = inventoryDatabase.itemDao()
//    }

}