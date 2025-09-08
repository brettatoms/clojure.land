import htmx from "htmx.org";

window.htmx = htmx;

function appendParameter(parameters: { [k: string ]: string | Array<string> }, key: string, value: string) {
    const values = parameters[key]
    if (typeof values === 'string') {
        parameters[key] = [values, value]
    } else if (Array.isArray(values)) {
        values.push(value)
    } else {
        parameters[key] = value
    }
}

// Hang these functions on window to make sure they're included in the bundle.
window.appendPlatformParameter = (event, platform: string) => {
    const el = document.querySelector(`input[value="${platform}"]`)
    if (el) {
        // cancel the request if the value already exists
        event.preventDefault()
        return
    }

    appendParameter(event.detail.parameters, 'platforms', platform)
};

window.appendTagParameter = (event, tag: string) => {
    console.log(event)
    const el = document.querySelector(`input[value="${tag}"]`)
    if (el) {
        // cancel the request if the value already exists
        event.preventDefault()
        return
    }

    appendParameter(event.detail.parameters, 'tags', tag)
};
