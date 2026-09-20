/* TidePool docs — shared navigation, in-page TOC, copy buttons.
 * No build step: plain HTML pages include this file and a <header id="site-header">. */
(function () {
  "use strict";

  var PAGES = [
    { href: "index.html", title: "首页" },
    { href: "getting-started.html", title: "快速开始" },
    { href: "configuration.html", title: "配置参数" },
    { href: "ftps.html", title: "FTPS" },
    { href: "spring-boot.html", title: "Spring Boot" },
    { href: "observability.html", title: "可观测性" },
    { href: "troubleshooting.html", title: "排错" }
  ];

  function currentFile() {
    var path = window.location.pathname;
    var file = path.substring(path.lastIndexOf("/") + 1);
    return file === "" ? "index.html" : file;
  }

  function buildNav() {
    var host = document.getElementById("site-header");
    if (!host) return;
    var current = currentFile();
    var nav = document.createElement("nav");
    nav.className = "topnav";
    nav.setAttribute("aria-label", "文档导航");

    var brand = document.createElement("a");
    brand.className = "brand";
    brand.href = "index.html";
    brand.innerHTML = "TidePool<span>FtpPool 开发者文档</span>";
    nav.appendChild(brand);

    PAGES.forEach(function (page) {
      var a = document.createElement("a");
      a.className = "nav-link" + (page.href === current ? " active" : "");
      a.href = page.href;
      a.textContent = page.title;
      if (page.href === current) a.setAttribute("aria-current", "page");
      nav.appendChild(a);
    });

    host.replaceWith(nav);
  }

  function slugify(text, index) {
    var slug = text
      .trim()
      .toLowerCase()
      .replace(/[^\w\u4e00-\u9fa5]+/g, "-")
      .replace(/^-+|-+$/g, "");
    return slug ? "sec-" + slug : "sec-" + index;
  }

  function buildToc() {
    var host = document.getElementById("toc");
    if (!host) return;
    var content = document.querySelector(".content");
    if (!content) return;

    var headings = content.querySelectorAll("h2, h3");
    if (!headings.length) {
      host.remove();
      return;
    }

    var root = document.createElement("ul");
    var currentSub = null;
    var currentSubItem = null;

    headings.forEach(function (heading, i) {
      if (!heading.id) heading.id = slugify(heading.textContent, i);

      var li = document.createElement("li");
      var a = document.createElement("a");
      a.href = "#" + heading.id;
      a.textContent = heading.textContent;
      li.appendChild(a);

      if (heading.tagName === "H2") {
        root.appendChild(li);
        currentSub = null;
        currentSubItem = li;
      } else {
        if (!currentSub) {
          currentSub = document.createElement("ul");
          (currentSubItem || root).appendChild(currentSub);
        }
        currentSub.appendChild(li);
      }
    });

    host.appendChild(root);
    spy(headings, host);
  }

  function spy(headings, tocHost) {
    if (!("IntersectionObserver" in window)) return;
    var links = {};
    tocHost.querySelectorAll("a").forEach(function (a) {
      links[a.getAttribute("href").slice(1)] = a;
    });

    var observer = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          var active = links[entry.target.id];
          if (!active) return;
          tocHost.querySelectorAll("a.active").forEach(function (a) {
            a.classList.remove("active");
          });
          active.classList.add("active");
        });
      },
      { rootMargin: "-72px 0px -70% 0px", threshold: 0 }
    );
    headings.forEach(function (h) {
      observer.observe(h);
    });
  }

  function addCopyButtons() {
    document.querySelectorAll("pre").forEach(function (pre) {
      var btn = document.createElement("button");
      btn.className = "copy-btn";
      btn.type = "button";
      btn.textContent = "复制";
      btn.addEventListener("click", function () {
        var code = pre.querySelector("code");
        var text = code ? code.innerText : pre.innerText;
        var done = function () {
          btn.textContent = "已复制";
          setTimeout(function () {
            btn.textContent = "复制";
          }, 1200);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(done, done);
        } else {
          var ta = document.createElement("textarea");
          ta.value = text;
          document.body.appendChild(ta);
          ta.select();
          try {
            document.execCommand("copy");
          } catch (e) {
            /* ignore */
          }
          document.body.removeChild(ta);
          done();
        }
      });
      pre.appendChild(btn);
    });
  }

  function init() {
    buildNav();
    buildToc();
    addCopyButtons();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
