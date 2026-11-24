use material_colors::{color::Rgb, hct::{solver::HctSolver, cam16::Cam16, viewing_conditions::ViewingConditions}};

/// Exact rounded constants embedded in the donor at .rodata0x46868.
/// Recomputing them with current Material defaults changes hue by a few millionths
/// of a degree and crosses a channel-rounding boundary in the donor grid corpus.
pub fn donor_viewing_conditions() -> ViewingConditions {
    let rgb_d=[1.021177769,0.98630774,0.933960497];
    ViewingConditions {
        adapting_luminance:11.725676537,
        background_lstar:50.0, surround:2.0, discounting_illuminant:false,
        background_ytowhite_point_y:0.184186503, aw:29.9810009,
        nbb:1.016919255, ncb:1.016919255, c:0.689999998, n_c:1.0,
        fl:0.388481468, f_lroot:0.789482653, z:1.909169555,
        white_point:[95.047,100.0,108.883], rgb_d,
        drgb_inverse:rgb_d.map(|x|1.0/x),
    }
}

pub fn donor_cam(seed:u32) -> Cam16 {
    let rgb=Rgb::new((seed>>16)as u8,(seed>>8)as u8,seed as u8);
    Cam16::from_rgb_in_viewing_conditions(rgb,&donor_viewing_conditions())
}

/// Exact recovered HyperOS tone recipe, conditional on the input seed already
/// matching libMiMainColor's separate image quantizer.
/// MainColorSingleRect::getPaletteColor, 0x20ca0..0x20d88:
/// chromas=[56,24,24,4,8]; tertiary hue+=60; primary=max(seed chroma,56).
/// Tone-index14=85, tone-index12=70; Java wrapper sets both alphas to153.
pub fn gradient_from_seed(seed: u32) -> [u32; 2] {
    let cam = donor_cam(seed);
    let top = HctSolver::solve_to_rgb(cam.hue + 60.0, 24.0, 85.0);
    let bottom = HctSolver::solve_to_rgb(cam.hue, cam.chroma.max(56.0), 70.0);
    [0x9900_0000 | top.as_u32(), 0x9900_0000 | bottom.as_u32()]
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn recovered_native_palette_samples() {
        assert_eq!(gradient_from_seed(0xffff_0000), [0x99ec_d199, 0x99ff_8a78]);
        assert_eq!(gradient_from_seed(0xff00_ff00), [0x99ae_dde0, 0x9903_c700]);
        assert_eq!(gradient_from_seed(0xff00_00ff), [0x99f6_c7e3, 0x999d_a3ff]);
        assert_eq!(gradient_from_seed(0xff40_7ebf), [0x99e6_cbf2, 0x9966_afff]);
    }
}
