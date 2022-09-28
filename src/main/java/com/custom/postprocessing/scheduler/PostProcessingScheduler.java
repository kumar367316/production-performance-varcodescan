package com.custom.postprocessing.scheduler;

import static com.custom.postprocessing.constant.PostProcessingConstant.ARCHIVE_DIRECTORY;
import static com.custom.postprocessing.constant.PostProcessingConstant.ARCHIVE_TEMP_BACKUP_DIRECTORY;
import static com.custom.postprocessing.constant.PostProcessingConstant.BACKSLASH_ASCII;
import static com.custom.postprocessing.constant.PostProcessingConstant.BANNER_DIRECTORY;
import static com.custom.postprocessing.constant.PostProcessingConstant.BANNER_PAGE;
import static com.custom.postprocessing.constant.PostProcessingConstant.EMPTY_SPACE;
import static com.custom.postprocessing.constant.PostProcessingConstant.FAILED_SUB_DIRECTORY;
import static com.custom.postprocessing.constant.PostProcessingConstant.FILE_SEPARATION;
import static com.custom.postprocessing.constant.PostProcessingConstant.LICENSE_DIRECTORY;
import static com.custom.postprocessing.constant.PostProcessingConstant.LOG_FILE;
import static com.custom.postprocessing.constant.PostProcessingConstant.OUTPUT_DIRECTORY;
import static com.custom.postprocessing.constant.PostProcessingConstant.PRINT_DIRECTORY;
import static com.custom.postprocessing.constant.PostProcessingConstant.PRINT_SUB_DIRECTORY;
import static com.custom.postprocessing.constant.PostProcessingConstant.PROCESS_DIRECTORY;
import static com.custom.postprocessing.constant.PostProcessingConstant.ROOT_DIRECTORY;
import static com.custom.postprocessing.constant.PostProcessingConstant.SPACE_VALUE;
import static com.custom.postprocessing.constant.PostProcessingConstant.TRANSIT_DIRECTORY;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.aspose.pdf.License;
import com.aspose.pdf.facades.PdfFileEditor;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.custom.postprocessing.constant.PostProcessingConstant;
import com.custom.postprocessing.util.EmailUtility;
import com.custom.postprocessing.util.PostProcessUtil;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlob;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlobDirectory;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;

/**
 * @author kumar.charanswain
 *
 */

@Service
public class PostProcessingScheduler {

	public static final Logger logger = LoggerFactory.getLogger(PostProcessingScheduler.class);

	@Value("${blob-account-name-key}")
	private String connectionNameKey;

	@Value("${blob-container-name}")
	private String containerName;

	@Value("#{'${state-allow-type}'.split(',')}")
	private List<String> batchType;

	@Value("#{'${page-type}'.split(',')}")
	private List<String> pageTypeList;

	@Value("${pcl-evaluation-copies}")
	private boolean pclEvaluationCopies;

	@Value("${license-file-name}")
	private String licenseFileName;

	@Autowired
	EmailUtility emailUtility;

	@Value("${selfAddressed-type}")
	private String selfAddressedType;

	@Autowired
	private PostProcessUtil postProcessUtil;

	List<String> invalidFileList = new LinkedList<>();

	@Scheduled(cron = "${cron-job-print-interval}")
	public void postProcessing() {
		String message = smartCommPostProcessing();
		deleteFiles(invalidFileList);
		logger.info(message);
	}

	public String smartCommPostProcessing() {
		String currentDate = currentDate();
		String currentDateTime = currentDateTimeStamp();
		String statusMessage = "";
		logger.info("postprocessing started");
		try {
			deletePreviousLogFile();
			final CloudBlobContainer container = containerinfo();
			String targetDirectory = OUTPUT_DIRECTORY + ARCHIVE_TEMP_BACKUP_DIRECTORY + "temp-archive_"
					+ currentDateTime + "/";
			moveSourceToTargetDirectory(OUTPUT_DIRECTORY + ARCHIVE_DIRECTORY, targetDirectory, currentDate,
					currentDateTime, container);
			String transitTargetDirectory = OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/" + currentDate + "-"
					+ PROCESS_DIRECTORY + "/" + currentDateTime + PRINT_SUB_DIRECTORY + "/";
			CloudBlobDirectory printDirectory = getDirectoryName(container, "", transitTargetDirectory);
			statusMessage = processMetaDataInputFile(printDirectory, currentDateTime, currentDate);

			processCompleteFile(currentDate);

			String logFile = LOG_FILE;
			File logFileName = new File(logFile + ".log");
			File updateLogFile = new File(logFile + "_" + currentDate + ".log");
			if (!(updateLogFile.exists())) {
				Files.copy(logFileName.toPath(), updateLogFile.toPath());
			}
			copyFileToTargetDirectory(updateLogFile.toString(), ROOT_DIRECTORY, "log");
			logFileName.delete();
			updateLogFile.delete();

		} catch (Exception exception) {
			logger.info("Exception smartComPostProcessing() " + exception.getMessage());
			statusMessage = "error in copy file to blob directory";
		}
		logger.info(statusMessage);
		logger.info("postprocessing ended");
		deleteFiles(invalidFileList);
		return statusMessage;
	}

	private void moveSourceToTargetDirectory(String sourceDirectory, String targetDirectory, String currentDate,
			String currentDateTime, CloudBlobContainer container) throws StorageException, IOException {
		BlobContainerClient blobContainerClient = getBlobContainerClient(connectionNameKey, containerName);
		Iterable<BlobItem> listBlobs = blobContainerClient.listBlobsByHierarchy(sourceDirectory);
		String targetProcessedPrintDirectory = OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/" + currentDate + "-"
				+ PROCESS_DIRECTORY + "/" + currentDateTime + PRINT_SUB_DIRECTORY + "/";
		try {
			CloudBlobDirectory archiveDirectory = getDirectoryName(container, "", OUTPUT_DIRECTORY + ARCHIVE_DIRECTORY);
			for (BlobItem blobItem : listBlobs) {
				String fileName = findActualFileName(blobItem.getName());
				String fileExt = FilenameUtils.getExtension(fileName);
				BlobClient dstBlobClient = blobContainerClient.getBlobClient(targetDirectory + fileName);
				BlobClient srcBlobClient = blobContainerClient.getBlobClient(blobItem.getName());
				String updateSrcUrl = srcBlobClient.getBlobUrl();
				if (srcBlobClient.getBlobUrl().contains(BACKSLASH_ASCII)) {
					updateSrcUrl = srcBlobClient.getBlobUrl().replace(BACKSLASH_ASCII, FILE_SEPARATION);
				}
				CloudBlockBlob cloudBlockBlob = archiveDirectory.getBlockBlobReference(fileName);
				cloudBlockBlob.downloadToFile(fileName);
				dstBlobClient.beginCopy(updateSrcUrl, null);
				srcBlobClient.delete();
				if (fileExt.equals("xml")) {
					boolean validXmlInputFIle = xmlFileDocumentReader(fileName, currentDate, currentDateTime);
					if (!validXmlInputFIle) {
						continue;
					}
					fileSeparateOperation(fileName, currentDate, currentDateTime, targetProcessedPrintDirectory);
				}
			}
		} catch (Exception exception) {
			logger.info("Exception moveSourceToTargetDirectory() " + exception.getMessage());
		}
	}

	public boolean inputXmlFileValidation(String inputXmlFile) {
		return validateXmlInputFile(inputXmlFile);
	}

	public void fileSeparateOperation(String fileName, String currentDate, String currentDateTime,
			String targetDirectory) {
		String xmlInputFile = fileName;
		String pdfInputFile = fileName.replace(".xml", ".pdf");
		if (fileName.contains("archiveOnly")) {
			boolean validFileCheck = inputXmlFileValidation(xmlInputFile);
			if (validFileCheck) {
				archiveOnlyOperation(pdfInputFile, currentDate, currentDateTime);
				archiveOnlyOperation(xmlInputFile, currentDate, currentDateTime);
			} else {
				failedFileProcessing(xmlInputFile, currentDate, currentDateTime);
				failedFileProcessing(pdfInputFile, currentDate, currentDateTime);
			}
		} else if (fileName.contains("printArchive") && !(fileName.contains("_CC_"))) {
			boolean validFileCheck = inputXmlFileValidation(xmlInputFile);
			if (validFileCheck) {
				archiveOnlyOperation(pdfInputFile, currentDate, currentDateTime);
				archiveOnlyOperation(xmlInputFile, currentDate, currentDateTime);
				copyFileToTargetDirectory(pdfInputFile, "", targetDirectory);
				copyFileToTargetDirectory(xmlInputFile, "", targetDirectory);
			} else {
				failedFileProcessing(xmlInputFile, currentDate, currentDateTime);
				failedFileProcessing(pdfInputFile, currentDate, currentDateTime);
			}
		} else if (fileName.contains("_CC_")) {
			boolean ccRecipientCountCheck = validateCCRecientFileType(fileName);
			boolean validFileCheck = inputXmlFileValidation(xmlInputFile);
			if (!(ccRecipientCountCheck) || !validFileCheck) {
				failedFileProcessing(xmlInputFile, currentDate, currentDateTime);
				failedFileProcessing(pdfInputFile, currentDate, currentDateTime);
			} else {
				archiveOnlyOperation(pdfInputFile, currentDate, currentDateTime);
				archiveOnlyOperation(xmlInputFile, currentDate, currentDateTime);
				fileName = FilenameUtils.removeExtension(fileName);
				String fileNameSplit[] = fileName.split("_");
				int ccNumber = 0;
				if (fileNameSplit.length >= 1) {
					ccNumber = Integer.parseInt(fileNameSplit[fileNameSplit.length - 1]);
				}
				primaryRecipeintOperation(xmlInputFile, pdfInputFile, ccNumber, currentDate, currentDateTime);
			}
		}
		new File(xmlInputFile).delete();
		new File(pdfInputFile).delete();
	}

	public String processMetaDataInputFile(CloudBlobDirectory transitDirectory, String currentDateTime,
			String currentDate) {
		Map<String, List<String>> postProcessMap = new HashMap<>();
		String statusMessage = "SmartComm PostProcessing completed successfully";
		try {
			Iterable<ListBlobItem> blobList = transitDirectory.listBlobs();
			for (ListBlobItem blobItem : blobList) {
				String fileName = getFileNameFromBlobURI(blobItem.getUri()).replace(SPACE_VALUE, EMPTY_SPACE);
				logger.info("process file:" + fileName);
				CloudBlockBlob intialFileDownload = transitDirectory.getBlockBlobReference(fileName);
				intialFileDownload.downloadToFile(fileName);
				boolean stateType = checkStateType(fileName);
				if (stateType) {
					if (stateType && !(fileName.contains("_CC_"))) {
						if (StringUtils.equalsIgnoreCase(FilenameUtils.getExtension(fileName), "xml")) {
							new File(fileName).delete();
							continue;
						}
						String fileNameNoExt = FilenameUtils.removeExtension(fileName);
						String[] stateAndSheetNameList = StringUtils.split(fileNameNoExt, "_");
						String stateAndSheetName = stateAndSheetNameList.length > 0
								? stateAndSheetNameList[stateAndSheetNameList.length - 1]
								: "";
						prepareMap(postProcessMap, stateAndSheetName, fileName);
					} else if (fileName.contains("_CC_") && !(fileName.contains("_Primary"))) {
						if ("pdf".equals(FilenameUtils.getExtension(fileName))) {
							new File(fileName).delete();
							continue;
						}
						prepareMap(postProcessMap, getSheetNumber(fileName, blobItem),
								StringUtils.replace(fileName, ".xml", "pdf"));
					} else if (fileName.contains("_Primary")) {
						if ("xml".equals(FilenameUtils.getExtension(fileName))) {
							new File(fileName).delete();
							continue;
						}
						String fileNameNoExt = FilenameUtils.removeExtension(fileName);
						String[] stateAndSheetNameList = fileNameNoExt.split("_ST_");
						if (stateAndSheetNameList.length >= 1) {
							String stateName = stateAndSheetNameList[stateAndSheetNameList.length - 1];
							String stateAndSheetName = stateName.substring(0, 2);
							prepareMap(postProcessMap, stateAndSheetName, fileName);
						}
					}
				} else if (checkPageType(fileName)) {
					if ("pdf".equals(FilenameUtils.getExtension(fileName))) {
						new File(fileName).delete();
						continue;
					}
					prepareMap(postProcessMap, getSheetNumber(fileName, blobItem),
							StringUtils.replace(fileName, ".xml", ".pdf"));
				} else if (fileName.contains(selfAddressedType)) {
					if (fileName.contains(selfAddressedType) && !(fileName.contains("_CC_"))) {
						if (StringUtils.equalsIgnoreCase(FilenameUtils.getExtension(fileName), "xml")) {
							new File(fileName).delete();
							continue;
						}
						String fileNameNoExt = FilenameUtils.removeExtension(fileName);
						String[] stateAndSheetNameList = StringUtils.split(fileNameNoExt, "_");
						String stateAndSheetName = stateAndSheetNameList.length > 0
								? stateAndSheetNameList[stateAndSheetNameList.length - 1]
								: "";
						prepareMap(postProcessMap, stateAndSheetName, fileName);
					} else if (fileName.contains("_CC_") && !(fileName.contains("_Primary"))) {
						if ("pdf".equals(FilenameUtils.getExtension(fileName))) {
							new File(fileName).delete();
							continue;
						}
						prepareMap(postProcessMap, getSheetNumber(fileName, blobItem),
								StringUtils.replace(fileName, ".xml", ".pdf"));
					} else if (fileName.contains("_Primary")) {
						if ("xml".equals(FilenameUtils.getExtension(fileName))) {
							new File(fileName).delete();
							continue;
						}
						String stateAndSheetName = "SelfAddressed";
						prepareMap(postProcessMap, stateAndSheetName, fileName);
					}
				} else {
					logger.info("processing batch type is not supported");
					continue;
				}
				new File(fileName).delete();
			}

			if (postProcessMap.size() > 0) {
				statusMessage = mergePDF(postProcessMap, currentDateTime, currentDate);
			} else {
				statusMessage = "no file for postprocessing";
			}
		} catch (Exception exception) {
			statusMessage = exception.getMessage();
			logger.info("Exception processMetaDataInputFile()" + exception.getMessage());
		}
		return statusMessage;
	}

	public void failedFileProcessing(String fileName, String currentDate, String currentDateTime) {
		logger.info("incorrect files for processing: " + fileName);
		String transitTargetDirectory = OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/" + currentDate + "-"
				+ PROCESS_DIRECTORY + "/" + currentDateTime + FAILED_SUB_DIRECTORY + "/";
		copyFileToTargetDirectory(fileName, "", transitTargetDirectory);
	}

	private String getSheetNumber(String fileName, ListBlobItem blobItem) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			// ET - added to mitigate vulnerability - Improper Restriction of XML External
			// Entity Reference CWE ID 611
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			File file = new File(fileName);
			CloudBlob cloudBlob = (CloudBlob) blobItem;
			cloudBlob.downloadToFile(file.getPath());
			Document document = xmlFileDocumentReader(fileName);
			Element root = document.getDocumentElement();
			if (Objects.isNull(root.getElementsByTagName("totalSheet").item(0))) {
				logger.info("xml file doesn't conains totalSheet element tag:" + fileName);
				file.delete();
				return PostProcessingConstant.ZEROPAGE;
			}
			int sheetNumber = Integer.parseInt(root.getElementsByTagName("totalSheet").item(0).getTextContent());
			if (sheetNumber <= 10) {
				file.delete();
				return String.valueOf(sheetNumber);
			}
			file.delete();
		} catch (Exception exception) {
			logger.info("Exception getSheetNumber()", exception.getMessage());
		}
		return PostProcessingConstant.MULTIPAGE;
	}

	public BlobContainerClient getBlobContainerClient(String connectionNameKey, String containerName) {
		BlobServiceClient blobServiceClient = new BlobServiceClientBuilder().connectionString(connectionNameKey)
				.buildClient();
		return blobServiceClient.getBlobContainerClient(containerName);
	}

	// post merge PDF
	public String mergePDF(Map<String, List<String>> postProcessMap, String currentDateTime, String currentDate)
			throws IOException {
		String statusMessage = "SmartComm PostProcessing completed successfully";
		List<String> fileNameList = new LinkedList<>();
		CloudBlobContainer container = containerinfo();
		MemoryUsageSetting memoryUsageSetting = MemoryUsageSetting.setupMainMemoryOnly();
		for (String fileType : postProcessMap.keySet()) {
			try {
				List<String> claimNbrSortedList = new LinkedList<>();
				PDFMergerUtility pdfMerger = new PDFMergerUtility();
				fileNameList = postProcessMap.get(fileType);
				String bannerFileName = getBannerPage(fileType);
				logger.info("banner file is:" + bannerFileName);
				File bannerFile = new File(bannerFileName);

				String blankPage = getEmptyPage();
				pdfMerger.addSource(bannerFileName);
				pdfMerger.addSource(blankPage);
				CloudBlobDirectory transitDirectory = getDirectoryName(container,
						OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/",
						currentDate() + "-" + PROCESS_DIRECTORY + "/" + currentDateTime + PRINT_SUB_DIRECTORY + "/");
				for (String fileName : fileNameList) {
					File file = new File(fileName);
					logger.info("process file for pcl is:" + fileName);
					CloudBlockBlob blob = transitDirectory.getBlockBlobReference(fileName);
					blob.downloadToFile(file.getAbsolutePath());
					String claimNbr = fileName.substring(14, fileName.length());
					File claimNbrFileName = new File(claimNbr);
					file.renameTo(claimNbrFileName);
					claimNbrSortedList.add(claimNbrFileName.toString());
					Collections.sort(claimNbrSortedList);
				}
				for (String fileName : claimNbrSortedList) {
					File file = new File(fileName);
					pdfMerger.addSource(file.getPath());
				}
				fileType = postProcessUtil.getFileType(fileType);
				String currentDateTimeStamp = currentDateTimeStamp();
				String mergePdfFile = fileType + "_" + currentDateTimeStamp + ".pdf";
				pdfMerger.setDestinationFileName(mergePdfFile);

				pdfMerger.mergeDocuments(memoryUsageSetting);

				statusMessage = convertPDFToPCL(mergePdfFile, container);
				bannerFile.delete();
				// new File(mergePdfFile).delete();
				new File(blankPage).delete();
				deleteFiles(claimNbrSortedList);
			} catch (StorageException storageException) {
				logger.info("invalid file or may be banner file is missing");
				statusMessage = storageException.getMessage();
				if (fileNameList.size() > 0) {
					deleteFiles(fileNameList);
				}
				continue;
			} catch (Exception exception) {
				statusMessage = exception.getMessage();
				if (fileNameList.size() > 0) {
					deleteFiles(fileNameList);
				}
				logger.info("Exception mergePDF()" + exception.getMessage());
				continue;
			}
		}
		File licenseFile = new File(licenseFileName);
		licenseFile.delete();

		return statusMessage;
	}

	// post processing PDF to PCL conversion
	public String convertPDFToPCL(String mergePdfFile, CloudBlobContainer container) throws IOException {
		String outputPclFile = FilenameUtils.removeExtension(mergePdfFile) + ".pcl";
		String statusMessage = "";
		try {
			CloudBlobDirectory transitDirectory = getDirectoryName(container, ROOT_DIRECTORY, LICENSE_DIRECTORY);
			CloudBlockBlob blob = transitDirectory.getBlockBlobReference(licenseFileName);
			String licenseFiles[] = blob.getName().split("/");
			String licenseFileName = licenseFiles[licenseFiles.length - 1];
			blob.downloadToFile(new File(licenseFileName).getAbsolutePath());
			License license = new License();
			license.setLicense(licenseFileName);
			statusMessage = pclFileCreation(mergePdfFile, outputPclFile);
		} catch (Exception exception) {
			statusMessage = "The license has expired:no need to print pcl file with evaluation copies";
		}
		if (pclEvaluationCopies) {
			statusMessage = "The license has expired:print pcl file with evaluation copies";
			pclFileCreation(mergePdfFile, outputPclFile);
		}
		new File(outputPclFile).delete();
		return statusMessage;
	}

	public void copyFileToTargetDirectory(String fileName, String rootDirectory, String targetDirectory) {
		try {
			CloudBlobContainer container = containerinfo();
			CloudBlobDirectory processDirectory = getDirectoryName(container, rootDirectory, targetDirectory);
			File outputFileName = new File(fileName);
			if (outputFileName.exists()) {
				CloudBlockBlob processSubDirectoryBlob = processDirectory.getBlockBlobReference(fileName);
				final FileInputStream inputStream = new FileInputStream(outputFileName);
				processSubDirectoryBlob.upload(inputStream, outputFileName.length());
				inputStream.close();
			}

		} catch (Exception exception) {
			logger.info("Exception copyFileToTargetDirectory() " + exception.getMessage());
		}
	}

	public boolean checkStateType(String fileName) {
		for (String state : batchType) {
			if (fileName.contains(state)) {
				return true;
			}
		}
		return false;
	}

	public boolean checkPageType(String fileName) {
		for (String pageType : pageTypeList) {
			if (fileName.contains(pageType)) {
				return true;
			}
		}
		return false;
	}

	public void deleteFiles(List<String> fileNameList) {
		for (String fileName : fileNameList) {
			File file = new File(fileName);
			if (file.exists()) {
				file.delete();
			}
		}
	}

	public void prepareMap(Map<String, List<String>> postProcessMap, String key, String fileName) {
		if (postProcessMap.containsKey(key)) {
			List<String> existingFileNameList = postProcessMap.get(key);
			existingFileNameList.add(fileName);
			postProcessMap.put(key, existingFileNameList);
		} else {
			List<String> existingFileNameList = new ArrayList<>();
			existingFileNameList.add(fileName);
			postProcessMap.put(key, existingFileNameList);
		}
	}

	public String getBannerPage(String key)
			throws URISyntaxException, StorageException, FileNotFoundException, IOException {
		CloudBlobContainer container = containerinfo();
		CloudBlobDirectory transitDirectory = getDirectoryName(container, ROOT_DIRECTORY, BANNER_DIRECTORY);
		String bannerFileName = BANNER_PAGE + key + ".pdf";
		CloudBlockBlob blob = transitDirectory.getBlockBlobReference(bannerFileName);
		File source = new File(bannerFileName);
		blob.downloadToFile(source.getAbsolutePath());
		return bannerFileName;
	}

	public String getEmptyPage() throws URISyntaxException, StorageException, FileNotFoundException, IOException {
		CloudBlobContainer container = containerinfo();
		CloudBlobDirectory transitDirectory = getDirectoryName(container, ROOT_DIRECTORY, BANNER_DIRECTORY);
		String blankPage = "Blank" + ".pdf";
		CloudBlockBlob blob = transitDirectory.getBlockBlobReference(blankPage);
		File source = new File(blankPage);
		blob.downloadToFile(source.getAbsolutePath());
		return blankPage;
	}

	public CloudBlobContainer containerinfo() {
		CloudBlobContainer container = null;
		try {
			CloudStorageAccount account = CloudStorageAccount.parse(connectionNameKey);
			CloudBlobClient serviceClient = account.createCloudBlobClient();
			container = serviceClient.getContainerReference(containerName);
		} catch (Exception exception) {
			logger.info("Exception containerinfo() " + exception.getMessage());
		}
		return container;
	}

	public String currentDate() {
		Date date = new Date();
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		return dateFormat.format(date);
	}

	public String currentDateTimeStamp() {
		Date date = new Date();
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");
		return dateFormat.format(date);
	}

	public CloudBlobDirectory getDirectoryName(CloudBlobContainer container, String directoryName,
			String subDirectoryName) throws URISyntaxException {
		CloudBlobDirectory cloudBlobDirectory = container.getDirectoryReference(directoryName);
		if (StringUtils.isBlank(subDirectoryName)) {
			return cloudBlobDirectory;
		}
		return cloudBlobDirectory.getDirectoryReference(subDirectoryName);
	}

	private String getFileNameFromBlobURI(URI blobUri) {
		final String[] fileNameList = blobUri.toString().split("/");
		Optional<String> fileName = Optional.empty();
		if (fileNameList.length > 1)
			fileName = Optional.ofNullable(fileNameList[fileNameList.length - 1]);
		return fileName.get();
	}

	public void deletePreviousLogFile() {
		LocalDate date = LocalDate.now();
		LocalDate previousDate = date.minusDays(1);
		File previousDayLogFile = new File("smartcommpostprocessing_backup" + "_" + previousDate + ".0" + ".log");
		if (previousDayLogFile.exists()) {
			previousDayLogFile.delete();
		}
	}

	public String getFileName(String sourceDirectory, String blobName) {
		String updateInputFIle = blobName.replace(sourceDirectory, "");
		return updateInputFIle;
	}

	public String findActualFileName(String fileName) {
		return fileName.replace(OUTPUT_DIRECTORY + ARCHIVE_DIRECTORY, "");
	}

	public String pclFileCreation(String mergePdfFile, String outputPclFile) {
		String statusMessage = "";
		try {
			PdfFileEditor fileEditor = new PdfFileEditor();
			final InputStream stream = new FileInputStream(mergePdfFile);
			final InputStream[] streamList = new InputStream[] { stream };
			final OutputStream outStream = new FileOutputStream(outputPclFile);
			fileEditor.concatenate(streamList, outStream);
			stream.close();
			outStream.close();
			fileEditor.setCloseConcatenatedStreams(true);
			String currentDate = currentDate();
			copyFileToTargetDirectory(outputPclFile, OUTPUT_DIRECTORY + TRANSIT_DIRECTORY, currentDate);
			logger.info("generate pcl file is:" + outputPclFile);
		} catch (Exception exception) {
			statusMessage = "error in pcl generate";
			logger.info("Exception pclFileCreation() " + exception.getMessage());
		}
		return statusMessage;
	}

	public String removeArchiveTotalSheetFileElement(File file, boolean renameFile, String currentDate,
			String currentDateTime, boolean fileDelete) {
		String updatedFile = "";
		try {
			if ("pdf".equals(FilenameUtils.getExtension(file.toString()))) {
				String[] splitFileName = file.toString().split("_");
				File updatePDFFile = new File(splitFileName[0] + ".pdf");
				file.renameTo(updatePDFFile);
				copyFileToTargetDirectory(updatePDFFile.toString(), OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/",
						currentDate + "-" + PROCESS_DIRECTORY + "/" + currentDateTime + "-" + ARCHIVE_DIRECTORY);

				copyFileToTargetDirectory(updatePDFFile.toString(), "", OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/"
						+ currentDate + "/" + currentDateTime + "-" + ARCHIVE_DIRECTORY);

				if (fileDelete) {
					updatePDFFile.delete();
				}
				updatedFile = updatePDFFile.toString();
			} else if ("xml".equals(FilenameUtils.getExtension(file.toString()))) {
				Document document = xmlFileDocumentReader(file.toString());
				Element root = document.getDocumentElement();
				String claimNumber = file.toString();

				final Node node = document.getElementsByTagName("Document").item(0);
				final NodeList nodeList = node.getChildNodes();
				for (int i = 0; i < nodeList.getLength(); i++) {
					final Node documentNode = nodeList.item(i);
					if (documentNode.getNodeName().equals("totalSheet")) {
						node.removeChild(documentNode);
					}
					if (documentNode.getNodeName().equals("DCN")) {
						claimNumber = root.getElementsByTagName("DCN").item(0).getTextContent();
					}
				}
				document.normalize();
				TransformerFactory transferFactory = TransformerFactory.newInstance();
				Transformer transformerReference = transferFactory.newTransformer();
				transformerReference.setOutputProperty(OutputKeys.INDENT, "yes");

				File updateXMLFile = new File(claimNumber + ".xml");

				final DOMSource source = new DOMSource(document);
				final StreamResult streamResult = new StreamResult(file);
				transformerReference.transform(source, streamResult);
				file.renameTo(updateXMLFile);
				copyFileToTargetDirectory(updateXMLFile.toString(), OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/",
						currentDate + "-" + PROCESS_DIRECTORY + "/" + currentDateTime + "-" + ARCHIVE_DIRECTORY);

				copyFileToTargetDirectory(updateXMLFile.toString(), "", OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/"
						+ currentDate + "/" + currentDateTime + "-" + ARCHIVE_DIRECTORY);

				if (fileDelete) {
					updateXMLFile.delete();
				}
				updatedFile = updateXMLFile.toString();
			}

		} catch (TransformerException fileTransferException) {
			logger.info("file trans former exception" + fileTransferException.getMessage());
		} catch (Exception exception) {
			logger.info("Exception archiveFileRemoveElement() " + exception.getMessage());
		}
		return updatedFile;
	}

	public void archiveOnlyOperation(String inputFile, String currentDate, String currentDateTime) {
		try {
			String fileExt = FilenameUtils.getExtension(inputFile);
			File file = new File(inputFile);
			File copyOriginalFile = new File("copyoriginal_" + inputFile);
			if (!(copyOriginalFile.exists())) {
				Files.copy(file.toPath(), copyOriginalFile.toPath());
			}
			if ("pdf".equals(fileExt)) {
				String[] splitFileName = file.toString().split("_");
				File updatePDFFile = new File(splitFileName[0] + ".pdf");
				copyOriginalFile.renameTo(updatePDFFile);
				copyFileToTargetDirectory(updatePDFFile.toString(), OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/",
						currentDate + "-" + PROCESS_DIRECTORY + "/" + currentDateTime + "-" + ARCHIVE_DIRECTORY);

				copyFileToTargetDirectory(updatePDFFile.toString(), "", OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/"
						+ currentDate + "/" + currentDateTime + "-" + ARCHIVE_DIRECTORY);

				updatePDFFile.delete();
			} else if ("xml".equals(fileExt)) {
				Document document = xmlFileDocumentReader(file.toString());
				Element root = document.getDocumentElement();
				String claimNumber = "";

				final Node node = document.getElementsByTagName("Document").item(0);
				final NodeList nodeList = node.getChildNodes();
				for (int i = 0; i < nodeList.getLength(); i++) {
					final Node documentNode = nodeList.item(i);
					if (documentNode.getNodeName().equals("totalSheet")) {
						node.removeChild(documentNode);
					}
					if (documentNode.getNodeName().equals("DCN")) {
						claimNumber = root.getElementsByTagName("DCN").item(0).getTextContent();
					}
				}
				document.normalize();
				TransformerFactory transferFactory = TransformerFactory.newInstance();
				Transformer transformerReference = transferFactory.newTransformer();
				transformerReference.setOutputProperty(OutputKeys.INDENT, "yes");

				File updateXMLFile = new File(claimNumber + ".xml");

				final DOMSource source = new DOMSource(document);
				final StreamResult streamResult = new StreamResult(copyOriginalFile);
				transformerReference.transform(source, streamResult);
				copyOriginalFile.renameTo(updateXMLFile);
				copyFileToTargetDirectory(updateXMLFile.toString(), OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/",
						currentDate + "-" + PROCESS_DIRECTORY + "/" + currentDateTime + "-" + ARCHIVE_DIRECTORY);

				copyFileToTargetDirectory(updateXMLFile.toString(), "", OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/"
						+ currentDate + "/" + currentDateTime + "-" + ARCHIVE_DIRECTORY);
				updateXMLFile.delete();
			}

		} catch (TransformerException fileTransferException) {
			logger.info("Exception archiveFileRemoveElement() " + fileTransferException.getMessage());
		} catch (Exception exception) {
			logger.info("Exception archiveFileRemoveElement() " + exception.getMessage());
		}
	}

	public Document xmlFileDocumentReader(String fileName)
			throws ParserConfigurationException, SAXException, IOException {
		Document document = null;
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			// ET - added to mitigate vulnerability - Improper Restriction of XML External
			// Entity Reference CWE ID 611
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			document = builder.parse(new File(fileName));
			document.getDocumentElement().normalize();
		} catch (Exception documentException) {
			logger.info("Exception xmlFileDocumentReader() :" + documentException.getMessage());
		}
		return document;
	}
	public void splitCCRecipeintPDFFile(File fileName, int recipeintCount, String currentDate, String currentDateTime) {
		try {
			PDDocument splitDocument = PDDocument.load(fileName);
			Splitter splitter = new Splitter();
			splitter.setSplitAtPage(2);
			List<PDDocument> Pages = splitter.split(splitDocument);
			Iterator<PDDocument> iterator = Pages.listIterator();
			int i = 1;
			int count = 0;
			List<String> pdfListFile = new LinkedList<>();
			String fileSplitName = FilenameUtils.removeExtension(fileName.toString());
			// PDDocument pdDocument = null;
			while (iterator.hasNext()) {
				String splitFileName = "split" + i++ + ".pdf";
				pdfListFile.add(splitFileName);
				PDDocument pdDocument = iterator.next();
				count++;
				pdDocument.save(splitFileName);
				pdDocument.close();
			}
			splitDocument.close();
			MemoryUsageSetting memoryUsageSetting = MemoryUsageSetting.setupMainMemoryOnly();
			PDFMergerUtility splitPdfMerger = new PDFMergerUtility();
			for (int a = recipeintCount + 1; a <= count; a++) {
				splitPdfMerger.addSource("split" + a + ".pdf");
			}
			splitPdfMerger.setDestinationFileName("primary" + ".pdf");
			splitPdfMerger.mergeDocuments(memoryUsageSetting);
			File primaryCCRecipeint = new File("primary" + ".pdf");
			File updatePrimaryFileName = new File(fileSplitName + "_Primary" + ".pdf");
			primaryCCRecipeint.renameTo(updatePrimaryFileName);
			copyFileToTargetDirectory(updatePrimaryFileName.toString(), OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/",
					currentDate + "-" + PROCESS_DIRECTORY + "/" + currentDateTime + "-" + PRINT_DIRECTORY);

			for (int j = 1; j <= recipeintCount; j++) {
				PDFMergerUtility pdfMerger = new PDFMergerUtility();
				pdfMerger.addSource("split" + j + ".pdf");
				pdfMerger.addSource(updatePrimaryFileName.toString());
				pdfMerger.setDestinationFileName(fileSplitName + "_" + j + ".pdf");
				pdfMerger.mergeDocuments(memoryUsageSetting);
				String ccRecipeintPDF = fileSplitName + "_" + j + ".pdf";
				copyFileToTargetDirectory(ccRecipeintPDF, "", OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/" + currentDate
						+ "-" + PROCESS_DIRECTORY + "/" + currentDateTime + PRINT_SUB_DIRECTORY + "/");
				new File(ccRecipeintPDF).delete();
			}
			deleteFiles(pdfListFile);
			updatePrimaryFileName.delete();
		} catch (Exception exception) {
			logger.info("exception:" + exception.getMessage());
		}
	}

	public void primaryRecipeintOperation(String xmlFile, String pdfFile, int ccNumberCount, String currentDate,
			String currentDateTime) {
		try {
			File pdfInputFile = new File(pdfFile.toString());
			splitCCRecipeintPDFFile(pdfInputFile, ccNumberCount, currentDate, currentDateTime);
			pdfInputFile.delete();

			File xmlInputFile = new File(xmlFile);
			Document document = xmlFileDocumentReader(xmlInputFile.toString());
			Element root = document.getDocumentElement();
			Integer sheetNumber = Integer.parseInt(root.getElementsByTagName("totalSheet").item(0).getTextContent());
			sheetNumber = sheetNumber - ccNumberCount;
			Integer numberOfPages = Integer
					.parseInt(root.getElementsByTagName("NumberOfPages").item(0).getTextContent());
			numberOfPages = numberOfPages - (ccNumberCount * 2);

			final Node node = document.getElementsByTagName("Document").item(0);
			final NodeList nodeList = node.getChildNodes();
			for (int i = 0; i < nodeList.getLength(); i++) {
				final Node documentNode = nodeList.item(i);
				if (documentNode.getNodeName().equals("NumberOfPages")) {
					documentNode.setTextContent(numberOfPages.toString());
				}
				if (documentNode.getNodeName().equals("totalSheet")) {
					documentNode.setTextContent(sheetNumber.toString());
				}
			}
			document.normalize();
			TransformerFactory transferFactory = TransformerFactory.newInstance();
			Transformer transformerReference = transferFactory.newTransformer();
			transformerReference.setOutputProperty(OutputKeys.INDENT, "yes");
			String updatePrimaryXmlName = FilenameUtils.removeExtension(xmlFile);

			File updateXmlFile = new File(updatePrimaryXmlName + "_Primary" + ".xml");
			final DOMSource source = new DOMSource(document);
			final StreamResult streamResult = new StreamResult(xmlInputFile);
			transformerReference.transform(source, streamResult);

			xmlInputFile.renameTo(updateXmlFile);

			copyFileToTargetDirectory(updateXmlFile.toString(), OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/",
					currentDate + "-" + PROCESS_DIRECTORY + "/" + currentDateTime + "-" + PRINT_DIRECTORY);

			splitCCRecipeintXmlFile(updateXmlFile, sheetNumber, numberOfPages, ccNumberCount, currentDate,
					currentDateTime);
			updateXmlFile.delete();

		} catch (Exception exception) {
			logger.info("exception primaryRecipeintOperation() " + exception.getMessage());
		}
	}

	public void splitCCRecipeintXmlFile(File xmlFile, Integer sheetNumber, Integer numberOfPages, int ccNumberCount,
			String currentDate, String currentDateTime) {
		String fileName = xmlFile.toString();
		try {
			for (int i = 1; i <= ccNumberCount; i++) {
				String newFileName = xmlFile.toString();
				newFileName = newFileName.replace("_Primary", "_" + i);

				Document document = xmlFileDocumentReader(fileName);
				if (i == 1) {
					sheetNumber = sheetNumber + 1;
					numberOfPages = numberOfPages + 2;
				}
				final Node node = document.getElementsByTagName("Document").item(0);
				final NodeList nodeList = node.getChildNodes();
				for (int j = 0; j < nodeList.getLength(); j++) {
					final Node documentNode = nodeList.item(j);
					if (documentNode.getNodeName().equals("NumberOfPages")) {
						documentNode.setTextContent(numberOfPages.toString());
					}
					if (documentNode.getNodeName().equals("totalSheet")) {
						documentNode.setTextContent(sheetNumber.toString());
					}
				}
				document.normalize();
				TransformerFactory transferFactory = TransformerFactory.newInstance();
				Transformer transformerReference = transferFactory.newTransformer();
				transformerReference.setOutputProperty(OutputKeys.INDENT, "yes");

				File updateXmlFile = new File(newFileName);
				final DOMSource source = new DOMSource(document);
				final StreamResult streamResult = new StreamResult(updateXmlFile);
				transformerReference.transform(source, streamResult);
				xmlFile.renameTo(updateXmlFile);
				copyFileToTargetDirectory(updateXmlFile.toString(), OUTPUT_DIRECTORY + TRANSIT_DIRECTORY + "/",
						currentDate + "-" + PROCESS_DIRECTORY + "/" + currentDateTime + "-" + PRINT_DIRECTORY);
				updateXmlFile.delete();
			}
			xmlFile.delete();
		} catch (Exception exception) {
			logger.info("Exception splitCCRecipeintXmlFile() " + exception.getMessage());
		}
	}

	public String findPrimaryFileName(String fileName, String splitType) {
		String updateFileName = "";
		if (StringUtils.isNoneEmpty(fileName)) {
			String fileNames[] = fileName.split(splitType);
			updateFileName = fileNames[0];
		}
		return updateFileName;
	}

	public void processCompleteFile(String currentDate) {
		try {
			String documentFileName = "process-completed" + ".txt";
			File file = new File(documentFileName);
			final FileOutputStream outputStream = new FileOutputStream(file);
			PrintWriter writer = new PrintWriter(outputStream);
			writer.println("process completed" + '\n');
			copyFileToTargetDirectory(file.toString(), OUTPUT_DIRECTORY + TRANSIT_DIRECTORY, currentDate);
			outputStream.close();
			writer.close();
			file.delete();
		} catch (Exception exception) {
			logger.info("Exception addAttachment() :" + exception.getMessage());
		}
	}

	public boolean xmlFileDocumentReader(String fileName, String currentDate, String currentDateTime)
			throws ParserConfigurationException, SAXException, IOException {
		boolean validaXmlFile = true;
		String pdfInputFile = fileName.replace(".xml", ".pdf");
		File pdfFile = new File(pdfInputFile);
		try {
			if (!(pdfFile.exists())) {
				failedFileProcessing(fileName, currentDate, currentDateTime);
				failedFileProcessing(pdfFile.toString(), currentDate, currentDateTime);
				pdfFile.delete();
				return false;
			}
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

			// ET - added to mitigate vulnerability - Improper Restriction of XML External
			// Entity Reference CWE ID 611
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document document = builder.parse(new File(fileName));
			document.getDocumentElement().normalize();
		} catch (Exception documentException) {
			logger.info("invalid xml for processing :" + fileName + " " + documentException.getMessage());
			validaXmlFile = false;
			invalidFileList.add(fileName);
		}
		if (!validaXmlFile) {
			failedFileProcessing(fileName, currentDate, currentDateTime);
			failedFileProcessing(pdfInputFile, currentDate, currentDateTime);
			pdfFile.delete();
		}
		return validaXmlFile;
	}

	public boolean validateXmlInputFile(String fileName) {
		boolean validXmlFile = true;
		try {
			//File file = new File(fileName);
			Document document = xmlFileDocumentReader(fileName);
			final Node node = document.getElementsByTagName("Document").item(0);
			if (Objects.isNull(node)) {
				logger.info("missing " + "Document" + " tag element");
				validXmlFile = false;
			}
			Node numberOfPagesList = document.getElementsByTagName("NumberOfPages").item(0);
			Node totalSheetTagList = document.getElementsByTagName("totalSheet").item(0);
			Node dcnNbr = document.getElementsByTagName("DCN").item(0);
			if (Objects.isNull(numberOfPagesList) || Objects.isNull(totalSheetTagList) || Objects.isNull(dcnNbr)) {
				logger.info("invalid xml input file : missing Document,totalSheet and DCN tag:" + fileName);
				validXmlFile = false;
			}
		} catch (Exception exception) {
			logger.info("Exception validateCCRecientXmlInputFile() " + exception.getMessage());
		}
		return validXmlFile;
	}

	public boolean validateCCRecientFileType(String fileName) {
		String fileNames[] = fileName.split("_");
		String updateCCNumber = fileNames[fileNames.length - 1];
		return updateCCNumber.matches(".*\\d.*");
	}
}