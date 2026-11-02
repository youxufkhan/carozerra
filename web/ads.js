// Load placements near the viewport, independently of player initialization.
const desktop = window.matchMedia("(min-width: 808px)");
const banner = document.getElementById("adBanner");
let bannerStarted = false;
let bannerKey;

function loadBanner() {
  const options = desktop.matches
    ? { key: "6d33916843669043d309748d1f48ef51", width: 728, height: 90 }
    : { key: "0c4d2ae1997df130389651f3751f9bf1", width: 320, height: 50 };
  if (options.key === bannerKey) return;
  bannerKey = options.key;
  banner.width = options.width;
  banner.height = options.height;
  // Keep the provider's global atOptions and parser-inserted script together
  // inside one document. Only the selected size is requested.
  banner.srcdoc = `<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
    <style>html,body{margin:0;padding:0;overflow:hidden}iframe{border:0}</style></head><body>
    <script>var atOptions = ${JSON.stringify({ ...options, format: "iframe", params: {} })};</script>
    <script data-cfasync="false" src="https://bauval.org/22/${options.key}"></script>
    </body></html>`;
}

desktop.addEventListener("change", () => {
  if (bannerStarted) loadBanner();
});

function loadPlacement(element) {
  if (element.id === "bannerAd") {
    bannerStarted = true;
    loadBanner();
  } else {
    const script = document.createElement("script");
    script.async = true;
    script.dataset.cfasync = "false";
    script.src = "https://bauval.org/21/ea3a9dd4947ab23327f651a2e40a6278";
    element.append(script);
  }
}

const placements = document.querySelectorAll("#bannerAd, #nativeAd");
if ("IntersectionObserver" in window) {
  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      observer.unobserve(entry.target);
      loadPlacement(entry.target);
    }
  }, { rootMargin: "200px" });
  placements.forEach((element) => observer.observe(element));
} else {
  placements.forEach(loadPlacement);
}
