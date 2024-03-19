package hu.fmdev.backend.controller;

import hu.fmdev.backend.service.PstSearchService;
import hu.fmdev.backend.util.FileWriterUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/find")
public class PstFinderController {

    @Autowired
    private PstSearchService searchService;

    @Autowired
    private FileWriterUtil fileWriterUtil;

    @GetMapping("/pst")
    public String searchAndWritePst() {
        try {
            String searchDirectory = "C:/Users/FabianM/Desktop";
            String outputFile = "C:/Users/FabianM/Desktop/found_psts.txt";

            List<String> paths = searchService.findPstFiles(searchDirectory);
            fileWriterUtil.writePathsToFile(paths, outputFile);

            return "Success";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
