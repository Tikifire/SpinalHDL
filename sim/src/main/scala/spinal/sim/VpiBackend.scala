package spinal.sim

import java.nio.file.{Paths, Files}
import java.io.{File, PrintWriter}
import scala.io.Source
import org.apache.commons.io.FileUtils

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.sys.process._

import spinal.sim.vpi._

class VpiBackendConfig {
  val rtlSourcesPaths        = ArrayBuffer[String]()
  var toplevelName: String   = null
  var pluginsPath: String    = "simulation_plugins"
  var workspacePath: String  = null
  var workspaceName: String  = null
  var wavePath: String       = null
  var waveFormat: WaveFormat = WaveFormat.NONE
  var analyzeFlags: String   = ""
  var runFlags: String       = ""
  var sharedMemSize          = 65536
  var CC: String             = "g++" 
  var CFLAGS: String         = "-std=c++11 -Wall -Wextra -pedantic -O2 -Wno-strict-aliasing" 
  var LDFLAGS: String        = "-lrt -lpthread " 
}

abstract class VpiBackend(val config: VpiBackendConfig) extends Backend {
  import config._
  import Backend._

  val sharedExtension = if(isWindows) "dll" else (if(isMac) "dylib" else "so")
  val sharedMemIfaceName = "shared_mem_iface." + sharedExtension
  val sharedMemIfacePath = pluginsPath + "/" + sharedMemIfaceName

  CFLAGS += " -fPIC -I " + pluginsPath
  CFLAGS += (if(isMac) " -dynamiclib " else "")
  LDFLAGS += (if(!isMac) " -shared" else "")

  val jdk = System.getProperty("java.home").replace("/jre","").replace("\\jre","")

  CFLAGS += s" -I$jdk/include -I$jdk/include/${(if(isWindows) "win32" 
                                                else (if (isMac) "darwin" 
                                                      else "linux"))}"

  class Logger extends ProcessLogger { 
    override def err(s: => String): Unit = {if(!s.startsWith("ar: creating ")) println(s)}
    override def out(s: => String): Unit = {}
    override def buffer[T](f: => T) = f
  }

  if(!Files.exists(Paths.get(sharedMemIfacePath))) { 
    List("/SharedMemIface.cpp", 
         "/SharedMemIface.hpp", 
         "/SharedMemIface_wrap.cxx", 
         "/SharedStruct.hpp").foreach { filename =>
           val cppSourceFile = new PrintWriter(new File(pluginsPath + "/" + filename))
           val stream = getClass.getResourceAsStream(filename)
           cppSourceFile.write(scala.io.Source.fromInputStream(stream).mkString) 
           cppSourceFile.close
         }

         assert(Process(Seq(CC, 
           "-c", 
           CFLAGS, 
           "SharedMemIface.cpp", 
           "-o",
           "SharedMemIface.o").mkString(" "), 
         new File(pluginsPath)).! (new Logger()) == 0, 
       "Compilation of SharedMemIface.cpp failed")

         assert(Process(Seq(CC, 
           "-c", 
           CFLAGS, 
           "SharedMemIface_wrap.cxx", 
           "-o",
           "SharedMemIface_wrap.o").mkString(" "), 
         new File(pluginsPath)).! (new Logger()) == 0, 
       "Compilation of SharedMemIface_wrap.cxx failed")

         assert(Process(Seq(CC, 
           CFLAGS, 
           "SharedMemIface.o", 
           "SharedMemIface_wrap.o",
           LDFLAGS, 
           "-o",
           sharedMemIfaceName).mkString(" "),

         new File(pluginsPath)).! (new Logger()) == 0, 
       "Compilation of SharedMemIface." + sharedExtension + " failed")
  }

  val pwd = new File(".").getAbsolutePath().mkString
  System.load(pwd + "/" + sharedMemIfacePath)

  def clean() {
    FileUtils.deleteQuietly(new File(s"${workspacePath}/${workspaceName}"))
    FileUtils.cleanDirectory(new File(pluginsPath))
  }

  def compileVPI()  // Return the plugin name
  def analyzeRTL()
  def runSimulation()
  def instanciate() = {
    compileVPI()
    analyzeRTL()
    val sharedMemIface = new SharedMemIface("SpinalHDL_" + uniqueId.toString, sharedMemSize)
    var shmemFile = new PrintWriter(new File(workspacePath + "/shmem_name"))
    shmemFile.write("SpinalHDL_" + uniqueId.toString) 
    shmemFile.close
    runSimulation()
    sharedMemIface
  }
}


class GhdlBackendConfig extends VpiBackendConfig {
  var ghdlPath: String = null
  var elaborationFlags: String = ""
}

class GhdlBackend(config: GhdlBackendConfig) extends VpiBackend(config) {
  import config._
  if(ghdlPath == null) ghdlPath = "ghdl"
  var vpiModuleName = "vpi_ghdl.vpi"

  def compileVPI() = {
    val vpiModulePath = pluginsPath + "/" + vpiModuleName
    if(!Files.exists(Paths.get(vpiModulePath))) {

      for(filename <- Array("/VpiPlugin.cpp", 
                            "/SharedStruct.hpp")) {
             var cppSourceFile = new PrintWriter(new File(pluginsPath + "/" + filename))
             var stream = getClass.getResourceAsStream(filename)
             cppSourceFile.write(scala.io.Source.fromInputStream(stream).mkString) 
             cppSourceFile.close
           }

           assert(Process(Seq(ghdlPath,
                              "--vpi-compile",
                              CC, 
                              "-c", 
                              CFLAGS, 
                              "VpiPlugin.cpp",
                              "-o",
                              "VpiPlugin.o").mkString(" "), 
                            new File(pluginsPath)).! (new Logger()) == 0, 
                  "Compilation of VpiPlugin.o failed")

            assert(Process(Seq(ghdlPath,
                              "--vpi-link",
                              CC, 
                              CFLAGS,
                              "VpiPlugin.o",
                              LDFLAGS,
                              "-o",
                              vpiModuleName).mkString(" "), 
                            new File(pluginsPath)).! (new Logger()) == 0, 
                  s"Compilation of $vpiModuleName failed")
    }
  } 

  def analyzeRTL() {

    val vhdlSourcePaths = rtlSourcesPaths.filter { s => (s.endsWith(".vhd") || 
                                                         s.endsWith(".vhdl")) }
                                         .mkString(" ")
    
    assert(Process(Seq(ghdlPath,
                       "-a",
                       analyzeFlags,
                       vhdlSourcePaths).mkString(" "), 
                     new File(workspacePath)).! (new Logger()) == 0, 
           s"Analyze step of vhdl files failed") 
  }

  def runSimulation() {
    val vpiModulePath = pluginsPath + "/" + vpiModuleName

    Future {
      assert(Process(Seq(ghdlPath,
                elaborationFlags,
                "--elab-run",
                toplevelName,
                s"--vpi=${pwd + "/" + vpiModulePath}",
                runFlags).mkString(" "), 
            new File(workspacePath)).! (new Logger()) == 0,
            s"Simulation of $toplevelName failed")
    }
  }
}

object GhdlBackend {

  def getMCODE(config: GhdlBackendConfig) = {
    if (config.ghdlPath == null) config.ghdlPath = "ghdl-mcode"
    val ghdlBackend = new GhdlBackend(config)
  ghdlBackend.vpiModuleName = "vpi_ghdl_mcode.vpi"
  ghdlBackend
  }

  def getGCC(config: GhdlBackendConfig) = {
    if (config.ghdlPath == null) config.ghdlPath = "ghdl-gcc"
    val ghdlBackend = new GhdlBackend(config)
  ghdlBackend.vpiModuleName = "vpi_ghdl_gcc.vpi"
  ghdlBackend
  }

  def getLLVM(config: GhdlBackendConfig) = {
    if (config.ghdlPath == null) config.ghdlPath = "ghdl-llvm"
    val ghdlBackend = new GhdlBackend(config)
  ghdlBackend.vpiModuleName = "vpi_ghdl_llvm.vpi"
  ghdlBackend
  }
}

class IVerilogBackendConfig extends VpiBackendConfig {
  var binDirectory: String = ""
}

class IVerilogBackend(config: IVerilogBackendConfig) extends VpiBackend(config) {

  import config._
  val vpiModuleName = "vpi_iverilog.vpi"
  val vpiModulePath = pluginsPath + "/" + vpiModuleName
  val iverilogPath    = binDirectory + "iverilog"
  val iverilogVpiPath = binDirectory + "iverilog-vpi"
  val vvpPath         = binDirectory + "vvp"

  val IVERILOGCFLAGS = "-Wstrict-prototypes".r
                                            .replaceAllIn(Process(Seq(iverilogVpiPath, 
                                                                      "--cflags")).!!,
                                                          "")
  val IVERILOGLDFLAGS = Process(Seq(iverilogVpiPath, "--ldflags")).!!
  val IVERILOGLDLIBS = Process(Seq(iverilogVpiPath, "--ldlibs")).!!

  def compileVPI() = {
    val vpiModulePath = pluginsPath + "/" + vpiModuleName
    if(!Files.exists(Paths.get(vpiModulePath))) {

      for(filename <- Array("/VpiPlugin.cpp", 
                            "/SharedStruct.hpp")) {
             var cppSourceFile = new PrintWriter(new File(pluginsPath + "/" + filename))
             var stream = getClass.getResourceAsStream(filename)
             cppSourceFile.write(scala.io.Source.fromInputStream(stream).mkString) 
             cppSourceFile.close
           }

           assert(Process(Seq(CC,
                              "-c", 
                              IVERILOGCFLAGS,
                              CFLAGS, 
                              "VpiPlugin.cpp",
                              "-o",
                              "VpiPlugin.o").mkString(" "), 
                            new File(pluginsPath)).! (new Logger()) == 0, 
                  "Compilation of VpiPlugin.o failed")

            assert(Process(Seq(CC,
                              IVERILOGCFLAGS,
                              CFLAGS,
                              "VpiPlugin.o",
                              IVERILOGLDFLAGS,
                              IVERILOGLDLIBS,
                              LDFLAGS,
                              "-o",
                              vpiModuleName).mkString(" "), 
                            new File(pluginsPath)).! (new Logger()) == 0, 
                  s"Compilation of $vpiModuleName failed")
    }
  } 

  def analyzeRTL() {
    val verilogSourcePaths = rtlSourcesPaths.filter { s => (s.endsWith(".v") || 
                                                           s.endsWith(".sv") ||
                                                           s.endsWith(".vl")) }
                                            .mkString(" ")

    assert(Process(Seq(iverilogPath,
                       analyzeFlags,
                       "-s",
                       toplevelName,
                       verilogSourcePaths,
                       "-o",
                       toplevelName + ".vvp").mkString(" "), 
                     new File(workspacePath)).! (new Logger()) == 0, 
           s"Analyze step of verilog files failed") 
  }

  def runSimulation() {
    val vpiModulePath = pluginsPath + "/" + vpiModuleName
    Future {
    assert(Process(Seq(vvpPath,
                "-M.",
                s"-m${pwd + "/" +vpiModulePath}",
                runFlags,
                toplevelName + ".vvp").mkString(" "), 
             new File(workspacePath)).! (new Logger()) == 0,
            s"Simulation of $toplevelName failed")
    }
  }
}



