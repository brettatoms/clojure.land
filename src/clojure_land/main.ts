import htmx from "htmx.org";

window.htmx = htmx;

addEventListener("DOMContentLoaded", (event) => {
    // populate the input value with the value attribute on page reload
    const input = document.querySelector("input#q");
    input.value = input.defaultValue;
});

window.appendPlatformInput = (platform: string) => {
    const input = document.createElement("input");
    input.setAttribute("name", "platforms");
    input.setAttribute("type", "hidden");
    input.setAttribute("value", platform);
    const form = document.querySelector("#form");
    form.appendChild(input);
};

window.appendTagInput = (tag: string) => {
    const input = document.createElement("input");
    input.setAttribute("name", "tags");
    input.setAttribute("type", "hidden");
    input.setAttribute("value", tag);
    const form = document.querySelector("#form");
    form.appendChild(input);
};
