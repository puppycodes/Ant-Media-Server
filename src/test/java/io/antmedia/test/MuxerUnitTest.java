package io.antmedia.test;

import static org.bytedeco.javacpp.avformat.avformat_close_input;
import static org.bytedeco.javacpp.avformat.avformat_find_stream_info;
import static org.bytedeco.javacpp.avformat.avformat_open_input;
import static org.bytedeco.javacpp.avformat.avio_alloc_context;
import static org.bytedeco.javacpp.avutil.*;
import static org.junit.Assert.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;

import org.apache.jasper.tagplugins.jstl.core.ForEach;
import org.apache.mina.core.buffer.IoBuffer;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.avformat;
import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacpp.avformat.AVFormatContext;
import org.bytedeco.javacpp.avformat.AVIOContext;
import org.bytedeco.javacpp.avutil.AVDictionary;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.red5.io.utils.IOUtils;
import org.red5.server.api.scheduling.IScheduledJob;
import org.red5.server.api.scheduling.ISchedulingService;
import org.red5.server.api.stream.IStreamListener;
import org.red5.server.api.stream.IStreamPacket;
import org.red5.server.scheduling.QuartzSchedulingService;
import org.red5.server.scope.WebScope;
import org.red5.server.service.mp4.impl.MP4Service;
import org.red5.server.stream.RemoteBroadcastStream;
import org.junit.Test;
import org.red5.codec.AudioCodec;
import org.red5.codec.VideoCodec;
import org.red5.io.ITag;
import org.red5.io.flv.impl.FLVReader;
import org.red5.io.flv.impl.FLVWriter;
import org.red5.io.mp4.impl.MP4Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.AbstractJUnit4SpringContextTests;
import org.springframework.util.ResourceUtils;

import com.antstreaming.rtsp.PacketSenderRunnable;

import io.antmedia.muxer.Muxer;
import io.antmedia.functional.MuxingTest;
//import io.antmedia.enterprise.ant_media_adaptive.TransraterAdaptor;
import io.antmedia.muxer.HLSMuxer;
import io.antmedia.muxer.Mp4Muxer;
import io.antmedia.muxer.MuxAdaptor;

@ContextConfiguration(locations = { 
		"test.xml" 
})
//@ContextConfiguration(classes = {AppConfig.class})
public class MuxerUnitTest extends AbstractJUnit4SpringContextTests{

	protected static Logger logger = LoggerFactory.getLogger(MuxerUnitTest.class);

	protected WebScope appScope;

	static {
		System.setProperty("red5.deployment.type", "junit");
		System.setProperty("red5.root", ".");
	}

	//TODO: rtsp ile yayın yapılacak, rtmp ile hls ile ve rtsp ile izlenecek
	//TODO: rtsp yayını bitince mp4 dosyası kontrol edilecek
	//TODO: desteklenmeyen bir codec ile rtsp datası gelince muxer bunu kontrol edecek


	@BeforeClass
	public static void beforeClass() {
		avformat.av_register_all();
		avformat.avformat_network_init();

		
	}
	
	@Before
	public void before() {
		File webApps = new File("webapps");
		if (!webApps.exists()) {
			webApps.mkdirs();
		}
		File junit = new File(webApps, "junit");
		if (!junit.exists()) {
			junit.mkdirs();
		}

		File streams = new File(junit, "streams");
		if (!streams.exists()) {
			streams.mkdirs();
		}
	}

	@After
	public void after() {
		try {
			delete(new File("webapps"));
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	}

	public static void delete(File file)
			throws IOException{

		if(file.isDirectory()){

			//directory is empty, then delete it
			if(file.list().length==0){

				file.delete();
				//System.out.println("Directory is deleted : " 
				//	+ file.getAbsolutePath());

			}else{

				//list all the directory contents
				String files[] = file.list();

				for (String temp : files) {
					//construct the file structure
					File fileDelete = new File(file, temp);

					//recursive delete
					delete(fileDelete);
				}

				//check the directory again, if empty then delete it
				if(file.list().length==0){
					file.delete();
					//System.out.println("Directory is deleted : " 
					//		+ file.getAbsolutePath());
				}
			}

		}else{
			//if file, then delete it
			file.delete();
			//System.out.println("File is deleted : " + file.getAbsolutePath());
		}
	}

	public class StreamPacket implements IStreamPacket {

		private ITag readTag;
		private IoBuffer data;

		public StreamPacket(ITag tag) {
			readTag = tag;
			data = readTag.getBody();
		}

		@Override
		public int getTimestamp() {
			return readTag.getTimestamp();
		}

		@Override
		public byte getDataType() {
			return readTag.getDataType();
		}

		@Override
		public IoBuffer getData() {
			return data;
		}

		public void setData(IoBuffer data) {
			this.data = data;
		}
	};

	//TODO: when prepare fails, there is memorly leak or thread leak?


	@Test
	public void testMuxingSimultaneously()  {

		MuxAdaptor muxAdaptor = new MuxAdaptor(null);
		muxAdaptor.setMp4MuxingEnabled(true,false);
		muxAdaptor.setHLSMuxingEnabled(true);
		muxAdaptor.setHLSFilesDeleteOnExit(false);

		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		//File file = new File(getResource("test.mp4").getFile());
		File file = null;

		try {

			QuartzSchedulingService scheduler = (QuartzSchedulingService) applicationContext.getBean(QuartzSchedulingService.BEAN_NAME);
			assertNotNull(scheduler);
			assertEquals(scheduler.getScheduledJobNames().size(), 0);

			file = new File("target/test-classes/test.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
			final FLVReader flvReader = new FLVReader(file);

			logger.debug("f path:" + file.getAbsolutePath());
			assertTrue(file.exists());

			boolean result = muxAdaptor.init(appScope, "test", false);
			assertTrue(result);

			muxAdaptor.start();

			int i = 0;
			while (flvReader.hasMoreTags()) 
			{
				ITag readTag = flvReader.readTag();
				StreamPacket streamPacket = new StreamPacket(readTag);
				muxAdaptor.packetReceived(null, streamPacket);
			}

			assertTrue(muxAdaptor.isRecording());

			muxAdaptor.stop();

			flvReader.close();

			while (muxAdaptor.isRecording()) {
				Thread.sleep(50);
			}


			assertFalse(muxAdaptor.isRecording());

			assertTrue(MuxingTest.testFile(muxAdaptor.getMuxerList().get(0).getFile().getAbsolutePath()));
			assertTrue(MuxingTest.testFile(muxAdaptor.getMuxerList().get(1).getFile().getAbsolutePath()));

		}
		catch (Exception e) {
			fail("exception:" + e );
		}

	}
	
	@Test
	public void testRemoteBroadcastStreamStartStop() 
	{
		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}
		
		QuartzSchedulingService scheduler = (QuartzSchedulingService) applicationContext.getBean(QuartzSchedulingService.BEAN_NAME);
		assertNotNull(scheduler);
		
		RemoteBroadcastStream rbs = new RemoteBroadcastStream();
		rbs.setRemoteStreamUrl("src/test/resources/test_short.flv");
		//boolean containsScheduler = thisScope.getParent().getContext().getApplicationContext().containsBean("rtmpScheduler");
		//if (containsScheduler) 
		
		rbs.setScheduler(scheduler);
		
		rbs.setName(UUID.randomUUID().toString());
		rbs.setConnection(null);
		rbs.setScope(appScope);
		
		rbs.start();
		
		
		while(scheduler.getScheduledJobNames().size() != 0) {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	
		
		assertEquals(0, rbs.getReferenceCountInQueue());
		
	}

	@Test
	public void testStressMp4Muxing() {

		long startTime = System.nanoTime();
		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		try {

			List<MuxAdaptor> muxAdaptorList = new ArrayList();
			for (int j = 0; j < 20; j++) {
				MuxAdaptor muxAdaptor = new MuxAdaptor(null);
				muxAdaptor.setMp4MuxingEnabled(true, true);
				muxAdaptor.setHLSMuxingEnabled(false);
				muxAdaptorList.add(muxAdaptor);
			}
			{

				File file = new File("src/test/resources/test.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
				final FLVReader flvReader = new FLVReader(file);

				logger.debug("f path: " + file.getAbsolutePath());
				assertTrue(file.exists());

				for (Iterator iterator = muxAdaptorList.iterator(); iterator.hasNext();) {
					MuxAdaptor muxAdaptor = (MuxAdaptor) iterator.next();
					boolean result = muxAdaptor.init(appScope, "test" + (int)(Math.random() * 1000), false);
					assertTrue(result);
					muxAdaptor.start();
					logger.info("Mux adaptor instance " + muxAdaptor);
				}


				while (flvReader.hasMoreTags()) 
				{
					ITag readTag = flvReader.readTag();
					StreamPacket streamPacket = new StreamPacket(readTag);
					for (MuxAdaptor muxAdaptor : muxAdaptorList) 
					{
						muxAdaptor.packetReceived(null, streamPacket);
						streamPacket.getData().rewind();
					}

				}

				for (MuxAdaptor muxAdaptor : muxAdaptorList) {
					while (!muxAdaptor.isRecording()) {
						Thread.sleep(50);
					}
				}
				
				//Thread.sleep(5000);


				for (MuxAdaptor muxAdaptor : muxAdaptorList) {
					muxAdaptor.stop();
				}


				flvReader.close();
				
				for (MuxAdaptor muxAdaptor : muxAdaptorList) {
					while (muxAdaptor.isRecording()) {
						Thread.sleep(50);
					}
				}


			}

			//Thread.sleep(15000);

			int count = 0;
			for (MuxAdaptor muxAdaptor : muxAdaptorList) {
				List<Muxer> muxerList = muxAdaptor.getMuxerList();
				for (Muxer abstractMuxer : muxerList) {
					if (abstractMuxer instanceof Mp4Muxer) {
						assertTrue(MuxingTest.testFile(abstractMuxer.getFile().getAbsolutePath(), 697000));
						count++;
					}
				}
			}

			assertEquals(muxAdaptorList.size(), count);
			
			long diff = (System.nanoTime() - startTime) / 1000000;
			
			System.out.println(" time diff: " + diff + " ms");


		}
		catch (Exception e) {

			e.printStackTrace();
			fail(e.getMessage());
		}

	}


	@Test
	public void testMp4MuxingWithSameName() {
		File file = testMp4Muxing("test_test");
		assertEquals("test_test.mp4", file.getName());

		file = testMp4Muxing("test_test");
		assertEquals("test_test_1.mp4", file.getName());
		
	

		file = testMp4Muxing("test_test");
		assertEquals("test_test_2.mp4", file.getName());
	}


	@Test
	public void testBaseStreamFileServiceBug() {
		MP4Service mp4Service = new MP4Service();

		String fileName = mp4Service.prepareFilename("mp4:1");
		assertEquals(fileName, "1.mp4");

		fileName = mp4Service.prepareFilename("mp4:12");
		assertEquals(fileName, "12.mp4");

		fileName = mp4Service.prepareFilename("mp4:123");
		assertEquals(fileName, "123.mp4");

		fileName = mp4Service.prepareFilename("mp4:1234");
		assertEquals(fileName, "1234.mp4");

		fileName = mp4Service.prepareFilename("mp4:12345");
		assertEquals(fileName, "12345.mp4");

		fileName = mp4Service.prepareFilename("mp4:123456");
		assertEquals(fileName, "123456.mp4");

		fileName = mp4Service.prepareFilename("mp4:1.mp4");
		assertEquals(fileName, "1.mp4");

		fileName = mp4Service.prepareFilename("mp4:123456789.mp4");
		assertEquals(fileName, "123456789.mp4");

	}

	@Test
	public void testMp4Muxing() {
		
		testMp4Muxing("hgjhg");
	}



	public File testMp4Muxing(String name) {

		MuxAdaptor muxAdaptor = new MuxAdaptor(null);
		muxAdaptor.setMp4MuxingEnabled(true, false);

		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		//File file = new File(getResource("test.mp4").getFile());
		File file = null;

		try {

			QuartzSchedulingService scheduler = (QuartzSchedulingService) applicationContext.getBean(QuartzSchedulingService.BEAN_NAME);
			assertNotNull(scheduler);
			assertEquals(scheduler.getScheduledJobNames().size(), 0);

			file = new File("target/test-classes/test.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
			final FLVReader flvReader = new FLVReader(file);

			logger.debug("f path:" + file.getAbsolutePath());
			assertTrue(file.exists());

			boolean result = muxAdaptor.init(appScope, name, false);
			assertTrue(result);


			muxAdaptor.start();

			int i = 0;
			while (flvReader.hasMoreTags()) 
			{
				ITag readTag = flvReader.readTag();
				StreamPacket streamPacket = new StreamPacket(readTag);
				muxAdaptor.packetReceived(null, streamPacket);

			}

			Thread.sleep(100);

			assertEquals(scheduler.getScheduledJobNames().size(), 1);
			assertTrue(muxAdaptor.isRecording());

			muxAdaptor.stop();

			flvReader.close();

			while (muxAdaptor.isRecording()) {
				Thread.sleep(50);
			}

			assertFalse(muxAdaptor.isRecording());

			assertEquals(scheduler.getScheduledJobNames().size(), 0);

			assertTrue(MuxingTest.testFile(muxAdaptor.getMuxerList().get(0).getFile().getAbsolutePath(), 697000));
			return muxAdaptor.getMuxerList().get(0).getFile();
		}
		catch (Exception e) {
			e.printStackTrace();
			fail("exception:" + e );
		}
		return null;
	}
	
	@Test
	public void testChangeAppSettingsMP4andHLS() {
		fail("implement this test");
	}
	
	@Test
	public void testCheckDefaultAppSettings(){
		fail("implement this test");
	}


	@Test
	public void testHLSMuxing()  {

		//av_log_set_level (40);

		MuxAdaptor muxAdaptor = new MuxAdaptor(null);
		muxAdaptor.setHLSMuxingEnabled(true);
		
		muxAdaptor.setMp4MuxingEnabled(false, false);
		int hlsListSize = 5;
		muxAdaptor.setHlsListSize(hlsListSize + "");
		int hlsTime = 2;
		muxAdaptor.setHlsTime(hlsTime + "");


		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		File file = null;
		try {

			QuartzSchedulingService scheduler = (QuartzSchedulingService) applicationContext.getBean(QuartzSchedulingService.BEAN_NAME);
			assertNotNull(scheduler);
			assertEquals(scheduler.getScheduledJobNames().size(), 0);

			file = new File("target/test-classes/test.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
			final FLVReader flvReader = new FLVReader(file);

			logger.debug("f path:" + file.getAbsolutePath());
			assertTrue(file.exists());

			boolean result = muxAdaptor.init(appScope, "testhls", false);
			assert(result);

			muxAdaptor.start();

			int i = 0;
			while (flvReader.hasMoreTags()) 
			{
				ITag readTag = flvReader.readTag();
				StreamPacket streamPacket = new StreamPacket(readTag);
				muxAdaptor.packetReceived(null, streamPacket);
			}

			Thread.sleep(1000);
			assertTrue(muxAdaptor.isRecording());

			muxAdaptor.stop();

			flvReader.close();

			while (muxAdaptor.isRecording()) {
				Thread.sleep(50);
			}

			assertFalse(muxAdaptor.isRecording());
		// delete job in the list
			assertEquals(1, scheduler.getScheduledJobNames().size());

			
			List<Muxer> muxerList = muxAdaptor.getMuxerList();
			HLSMuxer hlsMuxer = null;
			for (Muxer muxer : muxerList) {
				if (muxer instanceof HLSMuxer) {
					hlsMuxer = (HLSMuxer) muxer;
					break;
				}
			}
			assertNotNull(hlsMuxer);
			File hlsFile = hlsMuxer.getFile();
			
			String hlsFilePath = hlsFile.getAbsolutePath();
			int lastIndex =  hlsFilePath.lastIndexOf(".m3u8");
			
			String mp4Filename = hlsFilePath.substring(0, lastIndex) + ".mp4";

			//just check mp4 file is not created
			File mp4File = new File(mp4Filename);
			assertFalse(mp4File.exists());
			
			assertTrue(MuxingTest.testFile(hlsFile.getAbsolutePath()));

			File dir = new File(hlsFile.getAbsolutePath()).getParentFile();
			File[] files = dir.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.endsWith(".ts");
				}
			});

			System.out.println("ts file count:" + files.length);

			assertTrue(files.length < (int)Integer.valueOf(hlsMuxer.getHlsListSize()) * (Integer.valueOf(hlsMuxer.getHlsTime()) + 1));

			
			Thread.sleep(hlsListSize*hlsTime * 1000 + 3000);
			
			
			assertFalse(hlsFile.exists());
			
			files = dir.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					return name.endsWith(".ts");
				}
			});
			
			assertEquals(0, files.length);
			
			
		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}

	}

	@Test
	public void testRTSPMuxing() {

		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		try {

			File file = new File("target/test-classes/test.flv"); //ResourceUtils.getFile(this.getClass().getResource("test.flv"));
			PacketSenderRunnable rtspPacketSender = new PacketSenderRunnable(null);

			String sdpDescription = rtspPacketSender.getSdpDescription(file.getAbsolutePath());
			assertNotNull(sdpDescription);
			assertTrue(sdpDescription.length() > 0 );
			int[] clientPort = new int[2];
			clientPort[0] = 23458;
			clientPort[1] = 45567;
			int[] serverPort = new int[2];
			boolean result = rtspPacketSender.prepare_output_context(0, "127.0.0.1", clientPort, serverPort);
			assertTrue(result);

			int[] clientPort2 = new int[2];
			clientPort2[0] = 23452;
			clientPort2[1] = 44557;
			int[] serverPort2 = new int[2];
			result = rtspPacketSender.prepare_output_context(1, "127.0.0.1", clientPort2, serverPort2);
			assertTrue(result);

			ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) applicationContext.getBean("scheduler");
			assertNotNull(scheduler);

			scheduler.scheduleAtFixedRate(rtspPacketSender, 10);

			Thread.sleep(10000);


		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Test
	public void testSDPCreateBug() 
	{
		String file = "target/test-classes/test.flv";
		PacketSenderRunnable packetSender = new PacketSenderRunnable(null);
		String sdpDescription = packetSender.getSdpDescription(file);
		assertNotNull(sdpDescription);
		assertTrue(sdpDescription.length() > 0 );
		System.out.println(sdpDescription);

	}

}
