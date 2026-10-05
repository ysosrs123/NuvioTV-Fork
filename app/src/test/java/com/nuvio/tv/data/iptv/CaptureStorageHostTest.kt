package com.nuvio.tv.data.iptv

import com.nuvio.tv.core.iptv.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.FileStore
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.Base64
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Host JDK filesystem evidence only; Android statvfs has its own unexecuted fixtures. */
class CaptureStorageHostTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun realHostFilesystemProbeChecksTheTaskSpoolWithoutFillingOrChangingItsVolume() {
        var observations=0
        var last:CaptureSpaceReading?=null
        val probe=CaptureSpaceProbe { dir -> try { Files.getFileStore(dir.toPath()).let { fs ->
            observations++; CaptureSpaceReading(fs.usableSpace,allocationUnit(dir,fs),if (System.getProperty("os.name").startsWith("Windows")) fs.getAttribute("volume:vsn").toString() else fs.name()+fs.type()).also { last=it }
        } } catch(failure:Exception) { throw AssertionError("Host filesystem probe failed",failure) } }
        CaptureSegmentStore(temp.newFolder(),8192,4096,CaptureStoragePolicy(1024*1024,probe)).use { s ->
            s.checkStorageReservation(CaptureStorageReservation(8192,4096,requireNotNull(s.minimumStorageOverheadBytes)))
            s.append(0,1,0,ByteArrayInputStream(ByteArray(4096) { 42 }))
            s.open(0).use { assertEquals(4096,it.readBytes().size) }; assertTrue(observations>=6)
            val reading=requireNotNull(last)
            println("CAPTURE_STORAGE_HOST usableBytes=${reading.usableBytes} unitBytes=${reading.allocationUnitBytes} overheadBytes=${s.minimumStorageOverheadBytes} observations=$observations")
        }
    }
    private fun allocationUnit(directory: File, fs: FileStore): Long {
        if (!System.getProperty("os.name").startsWith("Windows")) return fs.blockSize
        // This host JDK returns bytesPerSector from getBlockSize, not sectorsPerCluster * bytesPerSector.
        // Read the actual Windows cluster via a task-owned noninteractive helper; no writes to the volume.
        val root=directory.toPath().root.toString().replace("'","''")
        val script="""
            ${'$'}ErrorActionPreference='Stop'
            ${'$'}ProgressPreference='SilentlyContinue'
            Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class NuvioCaptureGeometry { [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] public static extern bool GetDiskFreeSpace(string root, out uint sectors, out uint bytes, out uint free, out uint total); }'
            [uint32]${'$'}sectors=0
            [uint32]${'$'}bytes=0
            [uint32]${'$'}free=0
            [uint32]${'$'}total=0
            if (-not [NuvioCaptureGeometry]::GetDiskFreeSpace('$root',[ref]${'$'}sectors,[ref]${'$'}bytes,[ref]${'$'}free,[ref]${'$'}total)) { throw 'Filesystem geometry failed' }
            [uint64]${'$'}sectors * [uint64]${'$'}bytes
        """.trimIndent()
        val shell=File(requireNotNull(System.getenv("SystemRoot")),"System32/WindowsPowerShell/v1.0/powershell.exe").absolutePath
        val encoded=Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process=ProcessBuilder(shell,"-NoProfile","-NonInteractive","-EncodedCommand",encoded).start()
        try {
            process.outputStream.close()
            assertTrue("Task filesystem query timed out",process.waitFor(20,TimeUnit.SECONDS))
            val output=process.inputStream.bufferedReader().use { it.readText().trim() }
            val error=process.errorStream.bufferedReader().use { it.readText().trim() }
            assertEquals(error,0,process.exitValue())
            return output.toLong()
        } finally {
            if(process.isAlive) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS) }
            process.inputStream.close(); process.errorStream.close()
        }
    }
}
