package com.xingkeqi.btlogger.data

/**
 * @param imported 新写入的记录数
 * @param skipped 已存在而跳过的记录数（重复导入同一个文件不会产生重复记录）
 */
data class ImportResult(val imported: Int, val skipped: Int)

/**
 * 把从导出表格还原的记录写回数据库。
 *
 * 已存在的设备不覆盖（以本机最新采集为准），只补齐缺失的设备；记录按秒级去重。
 */
class RecordImporter(
    private val deviceDao: DeviceDao,
    private val recordDao: RecordDao
) {

    suspend fun import(records: List<ImportedRecord>): ImportResult {
        var imported = 0
        records.groupBy { it.device.mac }.forEach { (mac, items) ->
            if (deviceDao.getDeviceByMacSnapshot(mac) == null) {
                // 用最新一条记录的设备快照，名称、编解码等更接近当前状态
                deviceDao.insert(items.maxBy { it.record.timestamp }.device)
            }
            val fresh = items.map { it.record }
                .distinctBy { it.timestamp to it.eventType }
                .filter { record ->
                    val second = record.timestamp - record.timestamp % 1000
                    recordDao.countRecordsInRange(mac, record.eventType, second, second + 1000) == 0
                }
            if (fresh.isNotEmpty()) recordDao.insertAll(fresh)
            imported += fresh.size
        }
        return ImportResult(imported = imported, skipped = records.size - imported)
    }
}
