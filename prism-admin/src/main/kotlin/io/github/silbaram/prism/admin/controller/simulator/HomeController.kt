package io.github.silbaram.prism.admin.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class HomeController {

    @GetMapping("/")
    fun root(): String {
        return "redirect:/admin/experiments"
    }

    @GetMapping("/admin")
    fun admin(): String {
        return "redirect:/admin/experiments"
    }
}
