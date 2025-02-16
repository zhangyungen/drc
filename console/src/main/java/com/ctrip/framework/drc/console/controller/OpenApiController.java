package com.ctrip.framework.drc.console.controller;

import com.ctrip.framework.drc.console.service.OpenApiService;
import com.ctrip.framework.drc.console.vo.api.MhaGroupFilterVo;
import com.ctrip.framework.drc.core.http.ApiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @ClassName OpenApiController
 * @Author haodongPan
 * @Date 2022/1/4 17:49
 * @Version: $
 */
@RestController
@RequestMapping("/api/drc/v1/openapi/")
public class OpenApiController {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    
    @Autowired
    private OpenApiService openApiService;
    
    
    @GetMapping("info/mhas")
    @ResponseBody
    public ApiResult getDrcAllMhaDbFiltersInfo() {
        try {
            List<MhaGroupFilterVo> allDrcMhaDbFilters = openApiService.getAllDrcMhaDbFilters();
            return ApiResult.getSuccessInstance(allDrcMhaDbFilters);
        } catch (Exception e) {
            logger.error("error in getDrcAllMhaDb",e);
            return ApiResult.getFailInstance(e);
        }
    }


    @GetMapping("info/messengers")
    @ResponseBody
    public ApiResult getAllMessengersInfo() {
        try {
            return ApiResult.getSuccessInstance(openApiService.getAllMessengersInfo());
        } catch (Exception e) {
            logger.error("error in getAllMessengersInfo",e);
            return ApiResult.getFailInstance(e);
        }
    }
    
}
