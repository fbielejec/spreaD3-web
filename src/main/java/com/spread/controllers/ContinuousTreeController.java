package com.spread.controllers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import com.google.gson.GsonBuilder;
import com.spread.data.Attribute;
import com.spread.data.AxisAttributes;
import com.spread.data.Layer;
import com.spread.data.SpreadData;
import com.spread.data.TimeLine;
import com.spread.data.attributable.Area;
import com.spread.data.attributable.Line;
import com.spread.data.attributable.Point;
import com.spread.data.geojson.GeoJsonData;
import com.spread.domain.ContinuousAttributeEntity;
import com.spread.domain.ContinuousTreeModelEntity;
import com.spread.domain.HpdLevelEntity;
import com.spread.domain.IModel;
import com.spread.exceptions.SpreadException;
import com.spread.loggers.AbstractLogger;
import com.spread.loggers.ILogger;
import com.spread.loggers.LoggingUtils;
import com.spread.parsers.ContinuousTreeParser;
import com.spread.parsers.GeoJSONParser;
import com.spread.parsers.TimeParser;
import com.spread.repositories.ContinuousTreeModelRepository;
import com.spread.repositories.KeyRepository;
import com.spread.services.ipfs.IpfsService;
import com.spread.services.storage.StorageService;
import com.spread.services.visualization.VisualizationService;
import com.spread.utils.ParsersUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import io.jsonwebtoken.SignatureException;
import jebl.evolution.io.ImportException;
import jebl.evolution.trees.RootedTree;

@Controller
@CrossOrigin
@Transactional
@RequestMapping("/continuous")
public class ContinuousTreeController {

    private AbstractLogger logger;

    @Autowired
    private IpfsService ipfsService;

    @Autowired
    private ContinuousTreeModelRepository modelRepository;

    @Autowired
    private KeyRepository keyRepository;

    @Autowired
    @Qualifier("longRunningTaskExecutor")
    private Executor longRunningTaskExecutor;

    @Autowired
    private VisualizationService visualizationService;

    @Autowired
    private StorageService storageService;

    public ContinuousTreeController() {
    }

    public void init(AbstractLogger logger) {
        this.logger = logger;
    }

    /*
     * This path creates the entity
     * Reuploading tree file with the same sessionId is not possible
     * clients carrying the token should first delete the entity by calling the corresponding endpoint and ask for a new token.
     * */
    @RequestMapping(path = "/tree", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> uploadTree(HttpServletRequest request,
                                             @RequestHeader(value = "Authorization") String authorizationHeader,
                                             @RequestParam(value = "file", required = true) MultipartFile file) {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);

            if(!(modelRepository.findBySessionId(sessionId) == null)) {
                String message = "Session with that id already exists.";
                logger.log(ILogger.ERROR, message, new String[][] {
                        {"sessionId", sessionId},
                        {"request-ip" , request.getRemoteAddr()},
                    });
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ControllerUtils.jsonResponse(message));
            };

            String filename = file.getOriginalFilename();

            storageService.createSubdirectory(sessionId);
            storageService.store(sessionId, file);

            ContinuousTreeModelEntity model = new ContinuousTreeModelEntity(sessionId, storageService.loadAsResource(sessionId, filename).getFile().getAbsolutePath());

            RootedTree tree = ParsersUtils.importRootedTree(model.getTreeFilename());

            Set<ContinuousAttributeEntity> attributes = tree.getNodes().stream().filter(node -> !tree.isRoot(node))
                .flatMap(node -> node.getAttributeNames().stream()).map(name -> {
                        return new ContinuousAttributeEntity(name, model);
                    }).collect(Collectors.toSet());

            Set<HpdLevelEntity> hpdLevels = attributes.stream().map(attribute -> {
                    return attribute.getName();
                }).filter(attributeName -> attributeName.contains("HPD_modality"))
                .map(hpdString -> {
                        return new HpdLevelEntity(hpdString.replaceAll("\\D+", ""), model);
                    }).collect(Collectors.toSet());

            model.setAttributes(attributes);
            model.setHpdLevels(hpdLevels);

            modelRepository.save(model);

            logger.log(ILogger.INFO, "Tree file successfully persisted", new String[][] {
                    {"sessionId", sessionId},
                    {"filename", filename},
                    {"numberOfAttributes", String.valueOf(attributes.size())},
                    {"request-ip" , request.getRemoteAddr()},
                });

            return ResponseEntity.status(HttpStatus.CREATED).header("Location", sessionId).body(ControllerUtils.jsonResponse("OK"));
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("Authorication", "Bearer")
                .body(ControllerUtils.jsonResponse(e.getMessage()));
        } catch (IOException | ImportException e) {
            String message = Optional.ofNullable(e.getMessage()).orElse("Exception encountered when importing tree file");
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"message", message},
                    {"sessionId", sessionId},
                });
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ControllerUtils.jsonResponse("INTERNAL SERVER ERROR"));
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Authorication", "Bearer").body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        }
    }

    @RequestMapping(path = "/tree", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteTree(HttpServletRequest request,
                                             @RequestHeader(value = "Authorization") String authorizationHeader) {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);

            ContinuousTreeModelEntity model = modelRepository.findBySessionId(sessionId);

            if(model == null) {
                logger.log(ILogger.WARN, "No entity with that session id", new String[][] {
                        {"sessionId", sessionId},
                        {"request-ip", request.getRemoteAddr()},
                    });
                return ResponseEntity.status(HttpStatus.NO_CONTENT).body("No entity with that session id");
            }

            modelRepository.delete(model);
            storageService.deleteSubdirectory(sessionId);

            logger.log(ILogger.INFO, "Tree file successfully deleted", new String[][] {
                    {"sessionId", sessionId},
                    {"request-ip" , request.getRemoteAddr()},
                });

            return ResponseEntity.status(HttpStatus.OK).body(ControllerUtils.jsonResponse("OK"));
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ControllerUtils.jsonResponse(e.getMessage()));
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        }
    }

    @RequestMapping(path = "/hpd-level", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> setHpdLevel(HttpServletRequest request,
                                              @RequestHeader(value = "Authorization") String authorizationHeader,
                                              @RequestParam(value = "value", required = true) String hpdLevel) {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);

            Integer min = 0;
            Integer max = 100;
            if (!ControllerUtils.isInInterval(Integer.valueOf(hpdLevel), min, max)) {
                String message = "Hpd level parameter is outside of permitted interval";
                logger.log(ILogger.ERROR, message, new String[][] {
                        {"sessionId", sessionId},
                        {"value", hpdLevel.toString()},
                        {"min", min.toString()},
                        {"max", max.toString()},
                        {"request-ip" , request.getRemoteAddr()},
                    });
                String json = new GsonBuilder().create().toJson(Collections.singletonMap("response", message));
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(json);
            }

            ContinuousTreeModelEntity model = modelRepository.findBySessionId(sessionId);
            if(model == null) {
                String message = "Session with that id does not exist";
                logger.log(ILogger.WARN, message, new String[][] {
                        {"sessionId", sessionId},
                        {"request-ip", request.getRemoteAddr()},
                    });
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ControllerUtils.jsonResponse(message));
            };

            model.setHpdLevel(hpdLevel);
            modelRepository.save(model);

            logger.log(ILogger.INFO, "Hpd level parameter successfully set", new String[][] {
                    {"sessionId", sessionId},
                    {"hpdLevel", hpdLevel.toString()},
                });

            return ResponseEntity.status(HttpStatus.OK).body(ControllerUtils.jsonResponse("OK"));
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Authorication", "Bearer").body(ControllerUtils.jsonResponse("UNATHORIZED"));
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Authorication", "Bearer").body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        }
    }

    @RequestMapping(path = { "/coordinates/y", "/coordinates/latitude" }, method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> setyCoordinates(HttpServletRequest request,
                                                  @RequestHeader(value = "Authorization") String authorizationHeader,
                                                  @RequestParam(value = "value", required = true) String attribute) {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);

            ContinuousTreeModelEntity model = modelRepository.findBySessionId(sessionId);
            if(model == null) {
                String message = "Session with that id does not exist";
                logger.log(ILogger.WARN, message, new String[][] {
                        {"sessionId", sessionId},
                        {"request-ip", request.getRemoteAddr()}
                    });
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ControllerUtils.jsonResponse(message));
            };

            model.setyCoordinate(attribute);
            modelRepository.save(model);

            logger.log(ILogger.INFO, "y coordinate successfully set", new String[][] {
                    {"sessionId", sessionId},
                    {"attribute", attribute},
                    {"request-ip", request.getRemoteAddr()}
                });

            return ResponseEntity.status(HttpStatus.OK).body(ControllerUtils.jsonResponse("OK"));
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("Authorication", "Bearer")
                .body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("Authorication", "Bearer")
                .body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        }
    }

    @RequestMapping(path = { "/coordinates/x", "/coordinates/longitude" }, method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> setxCoordinates(HttpServletRequest request,
                                                  @RequestHeader(value = "Authorization") String authorizationHeader,
                                                  @RequestParam(value = "value", required = true) String attribute) {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);

            ContinuousTreeModelEntity model = modelRepository.findBySessionId(sessionId);
            if(model == null) {
                String message = "Session with that id does not exist";
                logger.log(ILogger.WARN, message, new String[][] {
                        {"sessionId", sessionId},
                        {"request-ip", request.getRemoteAddr()}
                    });
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ControllerUtils.jsonResponse(message));
            };

            model.setxCoordinate(attribute);
            modelRepository.save(model);

            logger.log(ILogger.INFO, "x coordinate successfully set", new String[][] {
                    {"sessionId", sessionId},
                    {"attribute", attribute},
                    {"request-ip", request.getRemoteAddr()}
                });

            return ResponseEntity.status(HttpStatus.OK).body(ControllerUtils.jsonResponse("OK"));
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("Authorication", "Bearer")
                .body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("Authorication", "Bearer")
                .body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        }
    }

    @RequestMapping(path = "/external-annotations", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> setHasExternalAnnotations(HttpServletRequest request,
                                                            @RequestHeader(value = "Authorization") String authorizationHeader,
                                                            @RequestParam(value = "value", required = true) Boolean hasExternalAnnotations) {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);

            ContinuousTreeModelEntity model = modelRepository.findBySessionId(sessionId);
            if(model == null) {
                String message = "Session with that id does not exist";
                logger.log(ILogger.WARN, message, new String[][] {
                        {"sessionId", sessionId},
                        {"request-ip", request.getRemoteAddr()}
                    });
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ControllerUtils.jsonResponse(message));
            };

            model.setHasExternalAnnotations(hasExternalAnnotations);
            modelRepository.save(model);

            logger.log(ILogger.INFO, "external annotations parameter successfully set", new String[][] {
                    {"sessionId", sessionId},
                    {"hasExternalAnnotations", hasExternalAnnotations.toString()},
                    {"request-ip", request.getRemoteAddr()}
                });

            return ResponseEntity.status(HttpStatus.OK).body(ControllerUtils.jsonResponse("OK"));
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Authorication", "Bearer").body(ControllerUtils.jsonResponse(e.getMessage()));
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Authorication", "Bearer").body("UNAUTHORIZED");
        }
    }

    @RequestMapping(path = "/mrsd", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> setMrsd(HttpServletRequest request,
                                          @RequestHeader(value = "Authorization") String authorizationHeader,
                                          @RequestParam(value = "value") String mrsd) {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);

            if (!TimeParser.isParseableDate(mrsd)) {
                String message = "Mrsd parameter is in a wrong format";
                logger.log(ILogger.ERROR, message, new String[][] {
                        {"sessionId", sessionId},
                        {"mrsd", mrsd},
                        {"formatToUse", "yyyy" + TimeParser.separator + "MM"
                         + TimeParser.separator + "dd"},
                        {"request-ip", request.getRemoteAddr()}
                    });
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ControllerUtils.jsonResponse(message));
            }

            ContinuousTreeModelEntity model = modelRepository.findBySessionId(sessionId);
            if(model == null) {
                String message = "Session with that id does not exist";
                logger.log(ILogger.WARN, message, new String[][] {
                        {"sessionId", sessionId},
                        {"request-ip", request.getRemoteAddr()}
                    });
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ControllerUtils.jsonResponse(message));
            };

            model.setMrsd(mrsd);
            modelRepository.save(model);

            logger.log(ILogger.INFO, "Mrsd parameter successfully set", new String[][] {
                    {"sessionId", sessionId},
                    {"mrsd", mrsd},
                    {"request-ip", request.getRemoteAddr()}
                });

            return ResponseEntity.status(HttpStatus.OK).body(ControllerUtils.jsonResponse("OK"));
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Authorication", "Bearer").body(ControllerUtils.jsonResponse(e.getMessage()));
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("Authorication", "Bearer").body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        }
    }

    @RequestMapping(path = "/timescale-multiplier", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> setTimescaleMultiplier(HttpServletRequest request,
                                                         @RequestHeader(value = "Authorization") String authorizationHeader,
                                                         @RequestParam(value = "value", required = true) Double timescaleMultiplier) {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);

            Double min = Double.MIN_NORMAL;
            Double max = Double.MAX_VALUE;

            if (!ControllerUtils.isInInterval(timescaleMultiplier, min, max)) {
                String message = "TimescaleMultiplier value is outside of permitted interval";
                logger.log(ILogger.ERROR, message, new String[][] {
                        {"sessionId", sessionId},
                        {"min", min.toString()},
                        {"max", max.toString()},
                        {"timescaleMultiplier", timescaleMultiplier.toString()},
                        {"request-ip", request.getRemoteAddr()}
                    });
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ControllerUtils.jsonResponse(message));
            }

            ContinuousTreeModelEntity model = modelRepository.findBySessionId(sessionId);
            if(model == null) {
                String message = "Session with that id does not exist";
                logger.log(ILogger.WARN, message, new String[][] {
                        {"sessionId", sessionId},
                        {"request-ip", request.getRemoteAddr()}
                    });
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ControllerUtils.jsonResponse(message));
            };
            model.setTimescaleMultiplier(timescaleMultiplier);
            modelRepository.save(model);

            logger.log(ILogger.INFO, "Timescale multiplier parameter successfully set", new String[][] {
                    {"sessionId", sessionId},
                    {"timescaleMultiplier", timescaleMultiplier.toString()},
                    {"request-ip", request.getRemoteAddr()}
                });

            return ResponseEntity.status(HttpStatus.OK).body(ControllerUtils.jsonResponse("OK"));
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ControllerUtils.jsonResponse(e.getMessage()));
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("Authorication", "Bearer")
                .body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        }
    }

    @RequestMapping(path = "/geojson", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> uploadGeojson(HttpServletRequest request,
                                                @RequestHeader(value = "Authorization") String authorizationHeader,
                                                @RequestParam(value = "file", required = true) MultipartFile file)  {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);

            String filename = file.getOriginalFilename();
            if (storageService.exists(sessionId, file)) {
                storageService.delete(sessionId, filename);
                logger.log(ILogger.INFO, "Deleting previously persisted geojson file", new String[][] {
                        {"sessionId", sessionId},
                        {"filename", filename},
                        {"request-ip", request.getRemoteAddr()}
                    });
            }

            storageService.store(sessionId, file);

            ContinuousTreeModelEntity model = modelRepository.findBySessionId(sessionId);
            model.setGeojsonFilename(storageService.loadAsResource(sessionId, filename).getFile().getAbsolutePath());
            modelRepository.save(model);

            logger.log(ILogger.INFO, "geojson file successfully persisted", new String[][] {
                    {"sessionId", sessionId},
                    {"filename", filename},
                    {"request-ip", request.getRemoteAddr()}
                });

            return new ResponseEntity<>(HttpStatus.CREATED);
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ControllerUtils.jsonResponse(e.getMessage()));
        } catch (IOException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                    {"request-ip", request.getRemoteAddr()}
                });
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ControllerUtils.jsonResponse("INTERNAL SERVER ERROR"));
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("Authorication", "Bearer")
                .body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        }
    }

    @RequestMapping(path = "/output", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> getOutput(HttpServletRequest request,
                                            @RequestHeader(value = "Authorization") String authorizationHeader) {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);
            ContinuousTreeModelEntity model = modelRepository.findBySessionId(sessionId);
            if(model == null) {
                String message = "Session with that id does not exist";
                logger.log(ILogger.WARN, message, new String[][] {
                        {"sessionId", sessionId},
                        {"request-ip", request.getRemoteAddr()}
                    });
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ControllerUtils.jsonResponse(message));
            };

            final String threadLocalSessionId = sessionId;
            longRunningTaskExecutor.execute(new Runnable() {
                    @Override
                    public void run() {

                        try {

                            logger.log(ILogger.INFO, "Generating output", new String[][] {
                                    {"sessionId", threadLocalSessionId},
                                    {"request-ip", request.getRemoteAddr()}
                                });

                            String json = doGenerateOutput(model);
                            storageService.write(threadLocalSessionId, "data.json", json.getBytes());

                            model.setOutputFilename(storageService.loadAsResource(threadLocalSessionId, "data.json").getFile().getAbsolutePath());
                            model.setStatus(IModel.Status.OUTPUT_READY);
                            modelRepository.save(model);

                            logger.log(ILogger.INFO, "Output succesfully generated", new String[][] {
                                    {"sessionId", threadLocalSessionId},
                                    {"request-ip", request.getRemoteAddr()}
                                });

                        } catch (Exception e) {
                            model.setStatus(IModel.Status.EXCEPTION_OCCURED);
                            modelRepository.save(model);

                            logger.log(ILogger.ERROR, e, new String[][] {
                                    {"sessionId", threadLocalSessionId},
                                    {"request-ip", request.getRemoteAddr()},
                                    {"stacktrace", LoggingUtils.getStackTrace(e)}
                                });
                        }
                    }
                });

            model.setStatus(IModel.Status.GENERATING_OUTPUT);
            modelRepository.save(model);

            logger.log(ILogger.INFO, "PUT /output", new String[][] {
                    {"sessionId", sessionId},
                    {"request-ip" , request.getRemoteAddr()}
                });

            // return immediately
            return ResponseEntity.status(HttpStatus.ACCEPTED).header("Location", sessionId).body(ControllerUtils.jsonResponse("ACCEPTED"));
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ControllerUtils.jsonResponse(e.getMessage()));
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        }
    }

    @RequestMapping(path = "/ipfs", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> putIpfs(HttpServletRequest request,
                                          @RequestHeader(value = "Authorization") String authorizationHeader) {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);

            ContinuousTreeModelEntity model = modelRepository.findBySessionId(sessionId);

            // run in background
            final String threadLocalSessionId = sessionId;

            longRunningTaskExecutor.execute(new Runnable() {
                    @Override
                    public void run() {

                        try {

                            logger.log(ILogger.INFO, "Copying visualisation", new String[][] {
                                    {"sessionId", threadLocalSessionId},
                                });

                            Path source = visualizationService.getVisualisationDirectory();
                            storageService.copy(threadLocalSessionId, source);

                            logger.log(ILogger.INFO, "Copied visualisation", new String[][] {
                                    {"sessionId", threadLocalSessionId},
                                    {"from", source.toString()},
                                    {"to", threadLocalSessionId},
                                });

                            logger.log(ILogger.INFO, "Publishing to ipfs", new String[][] {
                                    {"sessionId", threadLocalSessionId},
                                });

                            String hash = ipfsService.addDirectory(storageService.getSubdirectoryLocation(threadLocalSessionId));
                            model.setIpfsHash(hash);
                            model.setStatus(IModel.Status.IPFS_HASH_READY);
                            modelRepository.save(model);

                            logger.log(ILogger.INFO, "Published to ipfs", new String[][] {
                                    {"sessionId", threadLocalSessionId},
                                    {"ipfsHash", hash},
                                });

                        } catch (Exception e) {
                            model.setStatus(IModel.Status.EXCEPTION_OCCURED);
                            modelRepository.save(model);

                            logger.log(ILogger.ERROR, e, new String[][] {
                                    {"sessionId", threadLocalSessionId},
                                    {"stacktrace", LoggingUtils.getStackTrace(e)}
                                });
                        }
                    }
                });

            // return immediately
            model.setStatus(IModel.Status.PUBLISHING_IPFS);
            modelRepository.save(model);

            logger.log(ILogger.INFO, "PUT /ipfs", new String[][] {
                    {"sessionId", sessionId},
                    {"request-ip" , request.getRemoteAddr()}
                });

            return ResponseEntity.status(HttpStatus.ACCEPTED).header("Location", sessionId).body(ControllerUtils.jsonResponse("ACCEPTED"));
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ControllerUtils.jsonResponse(e.getMessage()));
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        }
    }

    @RequestMapping(path = "/ipfs", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getIpfsHash(HttpServletRequest request,
                                              @RequestHeader(value = "Authorization") String authorizationHeader) {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);

            ContinuousTreeModelEntity model = modelRepository.findBySessionId(sessionId);
            if(model == null) {
                String message = "Session with that id does not exist";
                logger.log(ILogger.WARN, message, new String[][] {
                        {"sessionId", sessionId},
                        {"request-ip", request.getRemoteAddr()},
                    });
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ControllerUtils.jsonResponse(message));
            };

            if(!(model.getStatus() == IModel.Status.IPFS_HASH_READY)) {
                String message = "Client should poll for status";
                return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location", "/continuous/status").body(ControllerUtils.jsonResponse(message));
            }

            logger.log(ILogger.INFO, "GET /ipfs", new String[][] {
                    {"sessionId", sessionId},
                    {"request-ip" , request.getRemoteAddr()}
                });

            return ResponseEntity.status(HttpStatus.OK).body(ControllerUtils.jsonResponse(model.getIpfsHash()));
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ControllerUtils.jsonResponse(e.getMessage()));
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("Authorication", "Bearer")
                .body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        }
    }

    @RequestMapping(path = "/status", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getStatus(HttpServletRequest request,
                                            @RequestHeader(value = "Authorization") String authorizationHeader) {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);

            ContinuousTreeModelEntity model = modelRepository.findBySessionId(sessionId);
            if(model == null) {
                String message = "Session with that id does not exist";
                logger.log(ILogger.WARN, message, new String[][] {
                        {"sessionId", sessionId},
                        {"request-ip", request.getRemoteAddr()}
                    });
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ControllerUtils.jsonResponse(message));
            };

            String status = model.getStatus().toString();
            String json = new GsonBuilder().create().toJson( Collections.singletonMap("status", status));

            logger.log(ILogger.INFO, "GET /status", new String[][] {
                    {"sessionId", sessionId},
                    {"status" , status},
                    {"request-ip" , request.getRemoteAddr()},
                });

            return ResponseEntity.status(HttpStatus.OK).body(json);
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ControllerUtils.jsonResponse(e.getMessage()));
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("Authorication", "Bearer")
                .body(ControllerUtils.jsonResponse("UNAUTHORIZED"));
        }
    }

    @RequestMapping(path = "/model", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ContinuousTreeModelEntity> getModel(HttpServletRequest request,
                                                              @RequestHeader(value = "Authorization") String authorizationHeader) {

        String sessionId = "null";

        try {

            String secret = keyRepository.findFirstByOrderByIdDesc().getKey();
            sessionId = ControllerUtils.getSessionId(authorizationHeader, secret);

            ContinuousTreeModelEntity model = modelRepository.findBySessionId(sessionId);
            if(model == null) {
                String message = "Session with that id does not exist";
                logger.log(ILogger.WARN, message, new String[][] {
                        {"sessionId", sessionId},
                        {"request-ip" , request.getRemoteAddr()}
                    });
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ContinuousTreeModelEntity());
            };

            logger.log(ILogger.INFO, "GET /model", new String[][] {
                    {"sessionId", sessionId},
                    {"yCoordinate", model.getyCoordinate()},
                    {"xCoordinate", model.getxCoordinate()},
                    {"mrsd", model.getMrsd()},
                    {"timescaleMultiplier", Optional.ofNullable(model.getTimescaleMultiplier()).toString()},
                    {"externalAnnotations", Optional.ofNullable(model.getHasExternalAnnotations()).toString()},
                    {"request-ip", request.getRemoteAddr()},
                });

            return ResponseEntity.status(HttpStatus.OK).body(model);
        } catch (SignatureException e) {
            logger.log(ILogger.ERROR, e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null);
        } catch (SpreadException e) {
            logger.log(ILogger.ERROR, e, new String[][] {
                    {"sessionId", sessionId},
                },
                e.getMeta());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("Authorication", "Bearer")
                .body(null);
        }
    }

    private String doGenerateOutput (ContinuousTreeModelEntity model) throws IOException, ImportException, SpreadException {

        TimeLine timeLine = null;
        LinkedList<Attribute> mapAttributes = null;
        LinkedList<Attribute> lineAttributes = null;
        LinkedList<Attribute> pointAttributes = null;
        LinkedList<Attribute> areaAttributes = null;
        LinkedList<Layer> layersList = new LinkedList<Layer>();

        // ---IMPORT---//

        RootedTree rootedTree = ParsersUtils.importRootedTree(model.getTreeFilename());
        TimeParser timeParser = new TimeParser(model.getMrsd());

        timeLine = timeParser.getTimeLine(rootedTree.getHeight(rootedTree.getRootNode()));

        // logger.log("Parsed time line", ILogger.INFO);

        ContinuousTreeParser treeParser = new ContinuousTreeParser(rootedTree, //
                                                                   model.getxCoordinate(), //
                                                                   model.getyCoordinate(), //
                                                                   model.hasExternalAnnotations(), //
                                                                   model.getHpdLevel(), //
                                                                   timeParser, //
                                                                   model.getTimescaleMultiplier());

        treeParser.parseTree();

        // logger.log("Parsed the tree", ILogger.INFO);

        lineAttributes = treeParser.getLineAttributes();
        pointAttributes = treeParser.getPointAttributes();
        areaAttributes = treeParser.getAreaAttributes();

        // logger.log("Parsed tree attributes", ILogger.INFO);

        // ---GEOJSON LAYER---//

        if (model.getGeojsonFilename() != null) {

            GeoJSONParser geojsonParser = new GeoJSONParser(model.getGeojsonFilename());
            GeoJsonData geojson = geojsonParser.parseGeoJSON();

            mapAttributes = geojsonParser.getUniqueMapAttributes();

            String geojsonLayerId = ParsersUtils.splitString(model.getGeojsonFilename(), "/");
            Layer geojsonLayer = new Layer(geojsonLayerId, //
                                           "GeoJson layer", //
                                           geojson);

            layersList.add(geojsonLayer);

            // logger.log("Parsed map attributes", ILogger.INFO);
        } // END: null check

        // ---DATA LAYER (TREE LINES & POINTS, AREAS)---//

        LinkedList<Line> linesList = treeParser.getLinesList();
        LinkedList<Point> pointsList = treeParser.getPointsList();
        LinkedList<Area> areasList = treeParser.getAreasList();

        String treeLayerId = ParsersUtils.splitString(model.getTreeFilename(), "/");
        Layer treeLayer = new Layer(treeLayerId, //
                                    "Tree layer", //
                                    pointsList, //
                                    linesList, //
                                    areasList);
        layersList.add(treeLayer);

        AxisAttributes axis = new AxisAttributes(model.getxCoordinate(),
                                                 model.getyCoordinate());

        SpreadData spreadData = new SpreadData(timeLine, //
                                               axis, //
                                               mapAttributes, //
                                               lineAttributes, //
                                               pointAttributes, //
                                               areaAttributes, //
                                               null, // locations
                                               layersList);

        return new GsonBuilder().create().toJson(spreadData);
    }

}
