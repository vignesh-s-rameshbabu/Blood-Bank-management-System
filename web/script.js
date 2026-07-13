document.addEventListener("DOMContentLoaded", () => {
    const mobileMenu = document.getElementById("mobile-menu");
    const navLinks = document.querySelector(".nav-links");
    const sidebar = document.querySelector(".sidebar");

    if (mobileMenu) {
        mobileMenu.addEventListener("click", () => {
            // Toggle top navigation if it exists
            if (navLinks) {
                navLinks.classList.toggle("active");
            }
            // Toggle sidebar if it exists (for dashboards)
            if (sidebar) {
                sidebar.classList.toggle("active");
            }
        });
    }
});
