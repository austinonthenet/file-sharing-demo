package com.example.fms.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpMediaType;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.MultipartContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.protobuf.ByteString;

import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.EntityAnnotation;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.Feature.Type;
import com.google.cloud.vision.v1.Image;
import com.google.cloud.vision.v1.ImageAnnotatorClient;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Controller
@Slf4j
@RequestMapping("/v1")
public class FileSharingController {

    @Value("${google.storage.bucket.name}")
    String bucketName;

    @Value("${google.project.id}")
    String projectId;
    
    //private ImageAnnotatorClient imageAnnotatorClient = ImageAnnotatorClient.create();

    @PutMapping("/{brand}/{relative_path}")
    public ResponseEntity<String> processUpload(HttpServletRequest request, @PathVariable(name = "brand") String brandId,
            @PathVariable(name = "relative_path") String relativePath) {

        byte[] body;

        try {

            body = request.getInputStream().readAllBytes();
            log.info("fileSize = " + body.length + " bytes.");
            Tika tika = new Tika();
            String mimeType = tika.detect(body);

            // TODO: call ClamAV for virus scan
            //HttpResponse response = makeScanRequest("https://clam-rest-service-3fxgfyig7a-uc.a.run.app/scan", "https://clam-rest-service-3fxgfyig7a-uc.a.run.app", mimeType, body, relativePath);
            HttpResponse response = makeScanRequest("http://10.128.0.2/scan", "https://clam-rest-service-3fxgfyig7a-uc.a.run.app", mimeType, body, relativePath);
            if (response == null) {
                log.error("response null !!");
                return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("Failed!!");
            } else if (!"OK".equals(response.getStatusMessage())) {
                if (response.getStatusCode() == 406) {
                    return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML).body("Infected!!" + response.getContent().readAllBytes());
                }
            }
            
            log.info("Response status: "+ new String(response.getStatusMessage()));
            
            if (imageProfanityFound(body)) {
                return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML).body("Profanity Detected!!");
            }

            // TODO: call Google Vision API for image profanity

            log.info("starting upload to bucket. Mime type = " + mimeType);
            Storage storage = StorageOptions.newBuilder().setProjectId(projectId).build().getService();
            BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, brandId + "/" + relativePath))
                    .setContentType(mimeType).build();

            storage.create(blobInfo, body);
            log.info("done upload to bucket.");
        } catch (HttpResponseException hre) { 
            if (hre.getStatusCode() == 406) {
                return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML).body("Infected!! " + hre.getMessage());
            }
            log.error("HttpResponseException ", hre);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("Failed!!");
        } catch (IOException ex) {
            body = new byte[0];
            log.error("Body parsing exception occurred", ex);
            // ex.printStackTrace();
        }

        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("Success!!");

    }
    
    public static boolean imageProfanityFound(byte[] data) {
        try (ImageAnnotatorClient vision = ImageAnnotatorClient.create()) {

            ByteString imgBytes = ByteString.copyFrom(data);

            // Builds the image annotation request
            List<AnnotateImageRequest> requests = new ArrayList<>();
            Image img = Image.newBuilder().setContent(imgBytes).build();
            Feature feat = Feature.newBuilder().setType(Type.SAFE_SEARCH_DETECTION).build();
            AnnotateImageRequest request =
                AnnotateImageRequest.newBuilder().addFeatures(feat).setImage(img).build();
            requests.add(request);

            // Performs label detection on the image file
            BatchAnnotateImagesResponse response = vision.batchAnnotateImages(requests);
            List<AnnotateImageResponse> responses = response.getResponsesList();

            log.info("Responses from Vision API: "+responses);
            for (AnnotateImageResponse res : responses) {
              if (res.hasError()) {
                System.out.format("Error: %s%n", res.getError().getMessage());
                return false;
              }

              for (EntityAnnotation annotation : res.getLabelAnnotationsList()) {
                annotation
                    .getAllFields()
                    .forEach((k, v) -> System.out.format("%s : %s%n", k, v.toString()));
              }
            }
          } catch (Exception e) {
              log.error("Exception calling Vision API", e);
              return false;
          }
        return false;
    }
    
    public static HttpResponse makeScanRequest(String serviceUrl, String audience, String contentType, byte[] contentBytes, String fileName) throws IOException {
        try {
            log.info("making inter service call...");
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
            if (!(credentials instanceof IdTokenProvider)) {
              throw new IllegalArgumentException("Credentials are not an instance of IdTokenProvider.");
            }
            
            log.info("credentials: "+credentials.toString());
            
            IdTokenCredentials tokenCredential =
                IdTokenCredentials.newBuilder()
                    .setIdTokenProvider((IdTokenProvider) credentials)
                    .setTargetAudience(audience)
                    .build();
    
            log.info("tokenCredential: "+tokenCredential.toString());
            
            GenericUrl genericUrl = new GenericUrl(serviceUrl);
            HttpCredentialsAdapter adapter = new HttpCredentialsAdapter(tokenCredential);
            HttpTransport transport = new NetHttpTransport();
            
            // Add parameters
            MultipartContent content = new MultipartContent().setMediaType(
                    new HttpMediaType("multipart/form-data")
                            .setParameter("boundary", "__END_OF_PART__"));
            // Add file
            ByteArrayContent fileContent = new ByteArrayContent(
                    contentType, contentBytes);
            MultipartContent.Part part = new MultipartContent.Part(fileContent);
            part.setHeaders(new HttpHeaders().set(
                    "Content-Disposition", 
                    String.format("form-data; name=\"content\"; filename=\"%s\"", fileName)));
            content.addPart(part);
            
            HttpRequest request = transport.createRequestFactory(adapter).buildPostRequest(genericUrl, content);
            return request.execute();
        } catch (HttpResponseException hre) { 
            throw hre;
        } catch (Exception e) {
            log.error("Exception calling other cloud-run service.", e);
        }
        return null;
    }
    
    public static HttpResponse makeGetRequest(String serviceUrl, String audience) throws IOException {
        try {
            log.info("making inter service call...");
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
            if (!(credentials instanceof IdTokenProvider)) {
              throw new IllegalArgumentException("Credentials are not an instance of IdTokenProvider.");
            }
            
            log.info("credentials: "+credentials.toString());
            
            IdTokenCredentials tokenCredential =
                IdTokenCredentials.newBuilder()
                    .setIdTokenProvider((IdTokenProvider) credentials)
                    .setTargetAudience(audience)
                    .build();
    
            log.info("tokenCredential: "+tokenCredential.toString());
            
            GenericUrl genericUrl = new GenericUrl(serviceUrl);
            HttpCredentialsAdapter adapter = new HttpCredentialsAdapter(tokenCredential);
            HttpTransport transport = new NetHttpTransport();
            HttpRequest request = transport.createRequestFactory(adapter).buildGetRequest(genericUrl);
            return request.execute();
        } catch (Exception e) {
            log.error("Exception calling other cloud-run service.", e);
        }
        return null;
    }

    @GetMapping("/{brand}/{relative_path}")
    public ResponseEntity<byte[]> processDownload(HttpServletRequest request,
            @PathVariable(name = "brand") String brandId, @PathVariable(name = "relative_path") String relativePath,
            HttpServletResponse response) {

        try {

            Storage storage = StorageOptions.newBuilder().setProjectId(projectId).build().getService();
            BlobId blobId = BlobId.of(bucketName, brandId + "/" + relativePath);

            log.info("starting download.");
            Blob blob = storage.get(blobId);
            if (blob != null) {
                String mimeType = blob.asBlobInfo().getContentType();

                return ResponseEntity.ok().contentType(MediaType.valueOf(mimeType)).body(blob.getContent());
            }
            log.info("done download.");

        } catch (Exception ex) {
            log.error("Body parsing exception occurred", ex);
        }

        return ResponseEntity.notFound().build();

    }

}
