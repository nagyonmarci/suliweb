//package hu.fmdev.backend.controller;
//
//import hu.fmdev.backend.service.PstSearchService;
//import hu.fmdev.backend.util.FileWriterUtil;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.CommandLineRunner;
//import org.springframework.stereotype.Component;
//
//import java.util.List;
//
//@Component
//public class CommandLineAppStartupRunner implements CommandLineRunner {
//
//    @Autowired
//    private PstSearchService searchService;
//
//    @Autowired
//    private FileWriterUtil fileWriterUtil;
//
//    @Override
//    public void run(String... args) throws Exception {
//        String searchDirectory = "C:/Users/FabianM/Desktop/";
//        String outputFile = "C:/Users/FabianM/Desktop/found_psts.txt";
//
//        List<String> paths = searchService.findPstFiles(searchDirectory);
//        fileWriterUtil.writePathsToFile(paths, outputFile);
//    }
//}